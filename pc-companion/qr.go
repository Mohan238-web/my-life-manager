package main

import (
	"fmt"
	"html"
	"strings"
)

// qrSVG creates a standards-compliant Version 5-L QR code in byte mode.
// The fixed version comfortably holds the short Corex pairing deep link.
func qrSVG(text string) (string, error) {
	data := []byte(text)
	const dataCodewords = 108
	const eccCodewords = 26
	if len(data) > 106 {
		return "", fmt.Errorf("pairing link is too long")
	}
	bits := make([]bool, 0, dataCodewords*8)
	appendBits := func(value, count int) {
		for i := count - 1; i >= 0; i-- {
			bits = append(bits, ((value>>i)&1) != 0)
		}
	}
	appendBits(4, 4) // byte mode
	appendBits(len(data), 8)
	for _, b := range data { appendBits(int(b), 8) }
	for i := 0; i < 4 && len(bits) < dataCodewords*8; i++ { bits = append(bits, false) }
	for len(bits)%8 != 0 { bits = append(bits, false) }
	codewords := make([]byte, 0, dataCodewords+eccCodewords)
	for i := 0; i < len(bits); i += 8 {
		var value byte
		for j := 0; j < 8; j++ { if bits[i+j] { value |= 1 << (7-j) } }
		codewords = append(codewords, value)
	}
	for pad := byte(0xec); len(codewords) < dataCodewords; pad ^= 0xfd {
		codewords = append(codewords, pad)
	}
	codewords = append(codewords, reedSolomonRemainder(codewords, eccCodewords)...)

	const size = 37
	modules := make([][]int8, size)
	for y := range modules {
		modules[y] = make([]int8, size)
		for x := range modules[y] { modules[y][x] = -1 }
	}
	set := func(x, y int, dark bool) {
		if x >= 0 && y >= 0 && x < size && y < size {
			if dark { modules[y][x] = 1 } else { modules[y][x] = 0 }
		}
	}
	drawFinder := func(cx, cy int) {
		for dy := -1; dy <= 7; dy++ {
			for dx := -1; dx <= 7; dx++ {
				x, y := cx+dx, cy+dy
				dark := dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6 &&
					(dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4))
				set(x, y, dark)
			}
		}
	}
	drawFinder(0, 0); drawFinder(size-7, 0); drawFinder(0, size-7)
	for i := 8; i < size-8; i++ {
		set(i, 6, i%2 == 0); set(6, i, i%2 == 0)
	}
	for dy := -2; dy <= 2; dy++ {
		for dx := -2; dx <= 2; dx++ {
			d := abs(dx); if abs(dy) > d { d = abs(dy) }
			set(30+dx, 30+dy, d != 1)
		}
	}
	// Reserve both copies of the 15 format bits before placing payload data.
	for i := 0; i <= 5; i++ { set(8, i, false) }
	set(8, 7, false); set(8, 8, false); set(7, 8, false)
	for i := 9; i < 15; i++ { set(14-i, 8, false) }
	for i := 0; i < 8; i++ { set(size-1-i, 8, false) }
	for i := 8; i < 15; i++ { set(8, size-15+i, false) }
	set(8, size-8, true)

	dataBits := make([]bool, 0, len(codewords)*8)
	for _, b := range codewords {
		for i := 7; i >= 0; i-- { dataBits = append(dataBits, ((b>>i)&1) != 0) }
	}
	bitIndex := 0
	upward := true
	for right := size - 1; right >= 1; right -= 2 {
		if right == 6 { right-- }
		for vert := 0; vert < size; vert++ {
			y := vert; if upward { y = size-1-vert }
			for j := 0; j < 2; j++ {
				x := right-j
				if modules[y][x] != -1 { continue }
				bit := false
				if bitIndex < len(dataBits) { bit = dataBits[bitIndex] }
				bitIndex++
				if (x+y)%2 == 0 { bit = !bit } // mask 0
				set(x, y, bit)
			}
		}
		upward = !upward
	}
	format := formatBits(1, 0) // L = binary 01, mask 0
	bit := func(i int) bool { return ((format>>i)&1) != 0 }
	for i := 0; i <= 5; i++ { set(8, i, bit(i)) }
	set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
	for i := 9; i < 15; i++ { set(14-i, 8, bit(i)) }
	for i := 0; i < 8; i++ { set(size-1-i, 8, bit(i)) }
	for i := 8; i < 15; i++ { set(8, size-15+i, bit(i)) }
	set(8, size-8, true)

	var out strings.Builder
	out.WriteString(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 45 45" shape-rendering="crispEdges" role="img" aria-label="Corex pairing QR code"><rect width="45" height="45" fill="white"/><path fill="#17211e" d="`)
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			if modules[y][x] == 1 { fmt.Fprintf(&out, "M%d %dh1v1h-1z", x+4, y+4) }
		}
	}
	out.WriteString(`"/><title>` + html.EscapeString(text) + `</title></svg>`)
	return out.String(), nil
}

func formatBits(level, mask int) int {
	data := (level << 3) | mask
	rem := data << 10
	for i := 14; i >= 10; i-- {
		if ((rem >> i) & 1) != 0 { rem ^= 0x537 << (i-10) }
	}
	return ((data << 10) | rem) ^ 0x5412
}

func reedSolomonRemainder(data []byte, degree int) []byte {
	divisor := make([]byte, degree)
	divisor[degree-1] = 1
	root := byte(1)
	for i := 0; i < degree; i++ {
		for j := 0; j < degree; j++ {
			divisor[j] = gfMultiply(divisor[j], root)
			if j+1 < degree { divisor[j] ^= divisor[j+1] }
		}
		root = gfMultiply(root, 2)
	}
	result := make([]byte, degree)
	for _, value := range data {
		factor := value ^ result[0]
		copy(result, result[1:])
		result[degree-1] = 0
		for i := range result { result[i] ^= gfMultiply(divisor[i], factor) }
	}
	return result
}

func gfMultiply(x, y byte) byte {
	var z byte
	for i := 7; i >= 0; i-- {
		z = (z << 1) ^ ((z >> 7) * 0x1d)
		z ^= ((y >> i) & 1) * x
	}
	return z
}

func abs(value int) int { if value < 0 { return -value }; return value }

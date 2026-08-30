package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	companionVersion = "1.0.0"
	defaultPort = 47625
	maxBody = 16 << 20
)

type peer struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Key        string `json:"key"`
	PairedAt   int64  `json:"pairedAt"`
	LastSeen   int64  `json:"lastSeen"`
}

type backup struct {
	Revision int64  `json:"revision"`
	SavedAt  int64  `json:"savedAt"`
	Snapshot string `json:"snapshot"`
}

type persistedState struct {
	ServerID string           `json:"serverId"`
	Peers    map[string]*peer `json:"peers"`
	Revision int64            `json:"revision"`
	Snapshot string           `json:"snapshot"`
	Backups  []backup         `json:"backups"`
}

type app struct {
	mu sync.Mutex
	state persistedState
	pairingCode string
	dataDir string
	port int
	seen map[string]int64
	exit chan struct{}
}

type envelope struct { IV string `json:"iv"`; Cipher string `json:"cipher"` }

func main() {
	log.SetFlags(log.LstdFlags | log.Lshortfile)
	a, err := newApp(defaultPort)
	if err != nil { showError(err); return }
	server := &http.Server{Addr: fmt.Sprintf(":%d", a.port), Handler:a.routes(), ReadHeaderTimeout:5*time.Second, ReadTimeout:20*time.Second, WriteTimeout:30*time.Second, MaxHeaderBytes:32<<10}
	listener, err := net.Listen("tcp", server.Addr)
	if err != nil {
		openBrowser(fmt.Sprintf("http://127.0.0.1:%d/", a.port))
		return
	}
	go func() {
		if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) { log.Printf("server: %v", err) }
	}()
	openBrowser(fmt.Sprintf("http://127.0.0.1:%d/", a.port))
	<-a.exit
	_ = server.Close()
}

func newApp(port int) (*app, error) {
	base, err := os.UserConfigDir(); if err != nil { return nil, err }
	dir := filepath.Join(base, "Corex Companion")
	if err := os.MkdirAll(dir, 0700); err != nil { return nil, err }
	a := &app{port:port, dataDir:dir, seen:map[string]int64{}, exit:make(chan struct{},1)}
	a.state = persistedState{Peers:map[string]*peer{}, Snapshot:"{}"}
	if err := a.load(); err != nil { log.Printf("starting with a new protected state: %v", err) }
	if a.state.ServerID == "" { a.state.ServerID = hex.EncodeToString(randomBytes(8)) }
	if a.state.Peers == nil { a.state.Peers = map[string]*peer{} }
	if !validSnapshot(a.state.Snapshot) { a.state.Snapshot = "{}" }
	a.pairingCode = fmt.Sprintf("%06d", randomNumber(1000000))
	return a, a.saveLocked()
}

func (a *app) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", a.dashboard)
	mux.HandleFunc("/dashboard/state", a.dashboardState)
	mux.HandleFunc("/dashboard/qr.svg", a.dashboardQR)
	mux.HandleFunc("/dashboard/update", a.dashboardUpdate)
	mux.HandleFunc("/dashboard/export", a.dashboardExport)
	mux.HandleFunc("/dashboard/rotate-pin", a.rotatePIN)
	mux.HandleFunc("/dashboard/autostart", a.autostart)
	mux.HandleFunc("/dashboard/exit", a.stop)
	mux.HandleFunc("/api/v1/info", a.info)
	mux.HandleFunc("/api/v1/pair", a.pair)
	mux.HandleFunc("/api/v1/sync/exchange", a.exchange)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		mux.ServeHTTP(w,r)
	})
}

func (a *app) info(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { methodNotAllowed(w); return }
	a.mu.Lock(); defer a.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]any{"serverId":a.state.ServerID,"serverName":computerName(),"port":a.port,"protocol":1,"version":companionVersion})
}

func (a *app) pair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost { methodNotAllowed(w); return }
	var request struct { DeviceID, DeviceName, ClientNonce, Proof string }
	if err := readJSON(r, &request); err != nil { writeError(w,http.StatusBadRequest,err.Error()); return }
	if !safeID(request.DeviceID) || request.ClientNonce == "" || request.Proof == "" { writeError(w,http.StatusBadRequest,"Invalid pairing request."); return }
	a.mu.Lock(); defer a.mu.Unlock()
	pairKey := pbkdf2SHA256([]byte(a.pairingCode), []byte("corex-pair:"+a.state.ServerID), 120000, 32)
	expected := hmacSHA256(pairKey, []byte("corex-pair|"+request.DeviceID+"|"+request.ClientNonce+"|"+a.state.ServerID))
	provided, err := base64.StdEncoding.DecodeString(request.Proof)
	if err != nil || subtle.ConstantTimeCompare(expected, provided) != 1 { writeError(w,http.StatusUnauthorized,"The pairing PIN is incorrect or expired."); return }
	key := randomBytes(32)
	now := time.Now().UnixMilli()
	a.state.Peers[request.DeviceID] = &peer{DeviceID:request.DeviceID,DeviceName:limit(request.DeviceName,80),Key:base64.StdEncoding.EncodeToString(key),PairedAt:now,LastSeen:now}
	if err := a.saveLocked(); err != nil { writeError(w,http.StatusInternalServerError,"Could not protect the pairing record."); return }
	secret, _ := json.Marshal(map[string]string{"peerKey":base64.StdEncoding.EncodeToString(key)})
	iv, ciphertext, err := seal(pairKey, secret, []byte(request.DeviceID+"|"+a.state.ServerID))
	if err != nil { writeError(w,http.StatusInternalServerError,"Pairing encryption failed."); return }
	writeJSON(w,http.StatusOK,envelope{IV:base64.StdEncoding.EncodeToString(iv),Cipher:base64.StdEncoding.EncodeToString(ciphertext)})
}

func (a *app) exchange(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost { methodNotAllowed(w); return }
	deviceID := r.Header.Get("X-Corex-Device")
	requestID := r.Header.Get("X-Corex-Request")
	stamp, err := strconv.ParseInt(r.Header.Get("X-Corex-Time"),10,64)
	if err != nil || abs64(time.Now().UnixMilli()-stamp) > 5*60*1000 || !safeID(deviceID) || requestID == "" { writeError(w,http.StatusUnauthorized,"The encrypted request expired."); return }
	var env envelope
	if err := readJSON(r,&env); err != nil { writeError(w,http.StatusBadRequest,err.Error()); return }
	a.mu.Lock(); defer a.mu.Unlock()
	a.pruneSeenLocked()
	seenKey := deviceID+"|"+requestID
	if _, exists := a.seen[seenKey]; exists { writeError(w,http.StatusConflict,"This encrypted request was already used."); return }
	peerRecord := a.state.Peers[deviceID]
	if peerRecord == nil { writeError(w,http.StatusUnauthorized,"Pair this phone again."); return }
	key, err := base64.StdEncoding.DecodeString(peerRecord.Key)
	if err != nil || len(key)!=32 { writeError(w,http.StatusUnauthorized,"Pair this phone again."); return }
	aad := []byte("POST|/api/v1/sync/exchange|"+strconv.FormatInt(stamp,10)+"|"+requestID)
	plain, err := openEnvelope(key,env,aad)
	if err != nil { writeError(w,http.StatusUnauthorized,"The encrypted payload could not be verified."); return }
	var request struct { Revision int64 `json:"revision"`; Snapshot, SnapshotHash string; UpdatedAt int64; ForcePhoneSnapshot bool }
	if err := json.Unmarshal(plain,&request); err != nil || !validSnapshot(request.Snapshot) { writeError(w,http.StatusBadRequest,"The Corex snapshot is invalid."); return }
	if !constantHexEqual(hashText(request.Snapshot), request.SnapshotHash) { writeError(w,http.StatusBadRequest,"The Corex snapshot checksum did not match."); return }
	a.seen[seenKey]=time.Now().UnixMilli()
	serverChanged := false
	if a.state.Revision == 0 && (request.Revision == 0 || request.ForcePhoneSnapshot) {
		if hashText(a.state.Snapshot) != hashText(request.Snapshot) { a.backupLocked(); a.state.Snapshot=request.Snapshot; a.state.Revision=1; serverChanged=true }
	} else if request.Revision == a.state.Revision && hashText(a.state.Snapshot) != hashText(request.Snapshot) {
		a.backupLocked(); a.state.Snapshot=request.Snapshot; a.state.Revision++; serverChanged=true
	} else if request.Revision > a.state.Revision {
		a.backupLocked(); a.state.Snapshot=request.Snapshot; a.state.Revision=request.Revision; serverChanged=true
	}
	peerRecord.LastSeen=time.Now().UnixMilli()
	if err:=a.saveLocked(); err!=nil { writeError(w,http.StatusInternalServerError,"The PC could not save the protected snapshot."); return }
	responsePlain,_:=json.Marshal(map[string]any{"revision":a.state.Revision,"snapshot":a.state.Snapshot,"changed":hashText(request.Snapshot)!=hashText(a.state.Snapshot),"saved":serverChanged})
	responseAAD:=[]byte("RESPONSE|/api/v1/sync/exchange|"+strconv.FormatInt(stamp,10)+"|"+requestID)
	iv,ciphertext,err:=seal(key,responsePlain,responseAAD)
	if err!=nil { writeError(w,http.StatusInternalServerError,"Response encryption failed."); return }
	writeJSON(w,http.StatusOK,envelope{IV:base64.StdEncoding.EncodeToString(iv),Cipher:base64.StdEncoding.EncodeToString(ciphertext)})
}

func (a *app) dashboard(w http.ResponseWriter, r *http.Request) {
	if !localRequest(r) { http.NotFound(w,r); return }
	if r.URL.Path!="/" { http.NotFound(w,r); return }
	w.Header().Set("Content-Type","text/html; charset=utf-8")
	_,_=io.WriteString(w,dashboardHTML)
}

func (a *app) dashboardState(w http.ResponseWriter, r *http.Request) {
	if !localRequest(r) { http.NotFound(w,r); return }
	a.mu.Lock(); defer a.mu.Unlock()
	peers:=make([]map[string]any,0,len(a.state.Peers))
	for _,p:=range a.state.Peers { peers=append(peers,map[string]any{"name":p.DeviceName,"lastSeen":p.LastSeen}) }
	sort.Slice(peers,func(i,j int)bool{return peers[i]["lastSeen"].(int64)>peers[j]["lastSeen"].(int64)})
	writeJSON(w,http.StatusOK,map[string]any{"version":companionVersion,"pin":a.pairingCode,"port":a.port,"serverId":a.state.ServerID,"addresses":privateIPv4(),"revision":a.state.Revision,"snapshot":a.state.Snapshot,"peers":peers,"autostart":autostartEnabled()})
}

func (a *app) dashboardQR(w http.ResponseWriter, r *http.Request) {
	if !localRequest(r) { http.NotFound(w,r); return }
	host:=r.URL.Query().Get("host")
	if !contains(privateIPv4(),host) { writeError(w,http.StatusBadRequest,"Choose a displayed PC address."); return }
	a.mu.Lock(); link:="corex://pair?host="+url.QueryEscape(host)+"&port="+strconv.Itoa(a.port)+"&code="+a.pairingCode+"&server="+a.state.ServerID; a.mu.Unlock()
	svg,err:=qrSVG(link); if err!=nil { writeError(w,http.StatusBadRequest,err.Error()); return }
	w.Header().Set("Content-Type","image/svg+xml; charset=utf-8"); _,_=io.WriteString(w,svg)
}

func (a *app) dashboardUpdate(w http.ResponseWriter, r *http.Request) {
	if !localRequest(r) { http.NotFound(w,r); return }
	if r.Method!=http.MethodPost { methodNotAllowed(w); return }
	var request struct{ Snapshot string `json:"snapshot"` }
	if err:=readJSON(r,&request);err!=nil||!validSnapshot(request.Snapshot){writeError(w,http.StatusBadRequest,"The edited Corex data is not valid.");return}
	a.mu.Lock();defer a.mu.Unlock();a.backupLocked();a.state.Snapshot=request.Snapshot;a.state.Revision++
	if err:=a.saveLocked();err!=nil{writeError(w,http.StatusInternalServerError,"The protected data file could not be saved.");return}
	writeJSON(w,http.StatusOK,map[string]any{"revision":a.state.Revision})
}

func (a *app) dashboardExport(w http.ResponseWriter,r *http.Request){
	if !localRequest(r){http.NotFound(w,r);return};a.mu.Lock();snapshot:=a.state.Snapshot;a.mu.Unlock()
	w.Header().Set("Content-Type","application/json");w.Header().Set("Content-Disposition",`attachment; filename="Corex-PC-Snapshot.json"`);_,_=io.WriteString(w,snapshot)
}

func (a *app) rotatePIN(w http.ResponseWriter,r *http.Request){
	if !localRequest(r){http.NotFound(w,r);return};if r.Method!=http.MethodPost{methodNotAllowed(w);return};a.mu.Lock();a.pairingCode=fmt.Sprintf("%06d",randomNumber(1000000));a.mu.Unlock();writeJSON(w,http.StatusOK,map[string]bool{"ok":true})
}

func (a *app) autostart(w http.ResponseWriter,r *http.Request){
	if !localRequest(r){http.NotFound(w,r);return};if r.Method!=http.MethodPost{methodNotAllowed(w);return}
	var request struct{Enabled bool `json:"enabled"`};if err:=readJSON(r,&request);err!=nil{writeError(w,http.StatusBadRequest,err.Error());return}
	if err:=setAutostart(request.Enabled);err!=nil{writeError(w,http.StatusInternalServerError,err.Error());return};writeJSON(w,http.StatusOK,map[string]bool{"enabled":request.Enabled})
}

func (a *app) stop(w http.ResponseWriter,r *http.Request){
	if !localRequest(r){http.NotFound(w,r);return};if r.Method!=http.MethodPost{methodNotAllowed(w);return};writeJSON(w,http.StatusOK,map[string]bool{"stopping":true});select{case a.exit<-struct{}{}:default:}
}

func (a *app) backupLocked(){
	if !validSnapshot(a.state.Snapshot){return};a.state.Backups=append(a.state.Backups,backup{Revision:a.state.Revision,SavedAt:time.Now().UnixMilli(),Snapshot:a.state.Snapshot});if len(a.state.Backups)>10{a.state.Backups=a.state.Backups[len(a.state.Backups)-10:]}
}

func (a *app) load() error{
	data,err:=os.ReadFile(filepath.Join(a.dataDir,"corex-state.dat"));if errors.Is(err,os.ErrNotExist){return nil};if err!=nil{return err};plain,err:=unprotect(data);if err!=nil{return err};return json.Unmarshal(plain,&a.state)
}

func (a *app) saveLocked() error{
	plain,err:=json.Marshal(a.state);if err!=nil{return err};data,err:=protect(plain);if err!=nil{return err};temp:=filepath.Join(a.dataDir,"corex-state.tmp");target:=filepath.Join(a.dataDir,"corex-state.dat");old:=filepath.Join(a.dataDir,"corex-state.bak");if err=os.WriteFile(temp,data,0600);err!=nil{return err};_ = os.Remove(old);if _,statErr:=os.Stat(target);statErr==nil{if err=os.Rename(target,old);err!=nil{return err}};if err=os.Rename(temp,target);err!=nil{_ = os.Rename(old,target);return err};_ = os.Remove(old);return nil
}

func (a *app) pruneSeenLocked(){cut:=time.Now().Add(-10*time.Minute).UnixMilli();for key,value:=range a.seen{if value<cut{delete(a.seen,key)}}}

func readJSON(r *http.Request,target any)error{defer r.Body.Close();decoder:=json.NewDecoder(io.LimitReader(r.Body,maxBody+1));if err:=decoder.Decode(target);err!=nil{return fmt.Errorf("invalid request")};return nil}
func writeJSON(w http.ResponseWriter,status int,value any){w.Header().Set("Content-Type","application/json; charset=utf-8");w.WriteHeader(status);_ = json.NewEncoder(w).Encode(value)}
func writeError(w http.ResponseWriter,status int,message string){writeJSON(w,status,map[string]string{"error":message})}
func methodNotAllowed(w http.ResponseWriter){writeError(w,http.StatusMethodNotAllowed,"Method not allowed.")}
func validSnapshot(value string)bool{text:=strings.TrimSpace(value);return strings.HasPrefix(text,"{")&&strings.HasSuffix(text,"}")&&json.Valid([]byte(text))&&len(text)<=maxBody}
func hashText(value string)string{sum:=sha256.Sum256([]byte(value));return hex.EncodeToString(sum[:])}
func constantHexEqual(a,b string)bool{return len(a)==len(b)&&subtle.ConstantTimeCompare([]byte(strings.ToLower(a)),[]byte(strings.ToLower(b)))==1}
func safeID(value string)bool{if len(value)<3||len(value)>128{return false};for _,r:=range value{if !(r=='-'||r=='_'||r=='.'||r>='0'&&r<='9'||r>='a'&&r<='z'||r>='A'&&r<='Z'){return false}};return true}
func limit(value string,size int)string{value=strings.TrimSpace(value);if len(value)>size{return value[:size]};return value}
func randomBytes(size int)[]byte{value:=make([]byte,size);if _,err:=rand.Read(value);err!=nil{panic(err)};return value}
func randomNumber(max int)int{b:=randomBytes(4);return int(uint32(b[0])<<24|uint32(b[1])<<16|uint32(b[2])<<8|uint32(b[3]))%max}
func hmacSHA256(key,value []byte)[]byte{mac:=hmac.New(sha256.New,key);_,_=mac.Write(value);return mac.Sum(nil)}
func pbkdf2SHA256(password,salt []byte,iterations,length int)[]byte{hLen:=32;blocks:=(length+hLen-1)/hLen;out:=make([]byte,0,blocks*hLen);for block:=1;block<=blocks;block++{suffix:=[]byte{byte(block>>24),byte(block>>16),byte(block>>8),byte(block)};u:=hmacSHA256(password,append(append([]byte{},salt...),suffix...));t:=append([]byte{},u...);for i:=1;i<iterations;i++{u=hmacSHA256(password,u);for j:=range t{t[j]^=u[j]}};out=append(out,t...)};return out[:length]}
func seal(key,plain,aad []byte)([]byte,[]byte,error){block,err:=aes.NewCipher(key);if err!=nil{return nil,nil,err};gcm,err:=cipher.NewGCM(block);if err!=nil{return nil,nil,err};iv:=randomBytes(gcm.NonceSize());return iv,gcm.Seal(nil,iv,plain,aad),nil}
func openEnvelope(key []byte,env envelope,aad []byte)([]byte,error){iv,err:=base64.StdEncoding.DecodeString(env.IV);if err!=nil{return nil,err};ciphertext,err:=base64.StdEncoding.DecodeString(env.Cipher);if err!=nil{return nil,err};block,err:=aes.NewCipher(key);if err!=nil{return nil,err};gcm,err:=cipher.NewGCM(block);if err!=nil{return nil,err};if len(iv)!=gcm.NonceSize(){return nil,errors.New("invalid nonce")};return gcm.Open(nil,iv,ciphertext,aad)}
func abs64(v int64)int64{if v<0{return -v};return v}
func localRequest(r *http.Request)bool{host,_,err:=net.SplitHostPort(r.RemoteAddr);if err!=nil{return false};ip:=net.ParseIP(host);return ip!=nil&&ip.IsLoopback()}
func privateIPv4()[]string{set:=map[string]bool{};interfaces,_:=net.Interfaces();for _,item:=range interfaces{if item.Flags&net.FlagUp==0||item.Flags&net.FlagLoopback!=0{continue};addresses,_:=item.Addrs();for _,address:=range addresses{var ip net.IP;switch v:=address.(type){case *net.IPNet:ip=v.IP;case *net.IPAddr:ip=v.IP};ip=ip.To4();if ip==nil||ip[0]==169&&ip[1]==254{continue};set[ip.String()]=true}};out:=make([]string,0,len(set));for value:=range set{out=append(out,value)};sort.Slice(out,func(i,j int)bool{return addressScore(out[i])<addressScore(out[j])});return out}
func addressScore(value string)int{if strings.HasPrefix(value,"192.168.42.")||strings.HasPrefix(value,"192.168.137."){return 0};if strings.HasPrefix(value,"192.168."){return 1};if strings.HasPrefix(value,"10."){return 2};return 3}
func contains(values []string,target string)bool{for _,value:=range values{if value==target{return true}};return false}
func computerName()string{name,_:=os.Hostname();if name==""{return "Corex PC"};return name}
func openBrowser(target string){var command *exec.Cmd;if runtime.GOOS=="windows"{command=exec.Command("rundll32","url.dll,FileProtocolHandler",target)}else if runtime.GOOS=="darwin"{command=exec.Command("open",target)}else{command=exec.Command("xdg-open",target)};_ = command.Start()}
func showError(err error){if err==nil{return};log.Printf("Corex Companion: %v",err)}
func setAutostart(enabled bool)error{if runtime.GOOS!="windows"{return errors.New("Start with Windows is available in the Windows EXE.")};exe,err:=os.Executable();if err!=nil{return err};if enabled{return exec.Command("reg","add",`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`,"/v","Corex Companion","/t","REG_SZ","/d",`"`+exe+`"`,"/f").Run()};return exec.Command("reg","delete",`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`,"/v","Corex Companion","/f").Run()}
func autostartEnabled()bool{if runtime.GOOS!="windows"{return false};return exec.Command("reg","query",`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`,"/v","Corex Companion").Run()==nil}

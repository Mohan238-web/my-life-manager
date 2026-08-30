package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestEncryptedPairAndSync(t *testing.T) {
	a:=&app{port:defaultPort,dataDir:t.TempDir(),pairingCode:"123456",seen:map[string]int64{},exit:make(chan struct{},1),state:persistedState{ServerID:"0011223344556677",Peers:map[string]*peer{},Snapshot:"{}"}}
	deviceID:="android-test-device"
	pairKey:=pbkdf2SHA256([]byte("123456"),[]byte("corex-pair:"+a.state.ServerID),120000,32)
	clientNonce:="client-nonce"
	proof:=base64.StdEncoding.EncodeToString(hmacSHA256(pairKey,[]byte("corex-pair|"+deviceID+"|"+clientNonce+"|"+a.state.ServerID)))
	pairBody,_:=json.Marshal(map[string]string{"deviceId":deviceID,"deviceName":"Test phone","clientNonce":clientNonce,"proof":proof})
	pairRequest:=httptest.NewRequest(http.MethodPost,"/api/v1/pair",strings.NewReader(string(pairBody)))
	pairResponse:=httptest.NewRecorder();a.pair(pairResponse,pairRequest)
	if pairResponse.Code!=http.StatusOK{t.Fatalf("pair status %d: %s",pairResponse.Code,pairResponse.Body.String())}
	var pairEnvelope envelope;if err:=json.Unmarshal(pairResponse.Body.Bytes(),&pairEnvelope);err!=nil{t.Fatal(err)}
	pairPlain,err:=openEnvelope(pairKey,pairEnvelope,[]byte(deviceID+"|"+a.state.ServerID));if err!=nil{t.Fatal(err)}
	var secret map[string]string;if err=json.Unmarshal(pairPlain,&secret);err!=nil{t.Fatal(err)}
	peerKey,err:=base64.StdEncoding.DecodeString(secret["peerKey"]);if err!=nil||len(peerKey)!=32{t.Fatalf("invalid peer key")}

	phoneSnapshot:=`{"corex.notes":"[{\"title\":\"Test\"}]"}`
	response:=exchangeForTest(t,a,deviceID,peerKey,0,phoneSnapshot,"request-one")
	if response.Revision!=1||response.Snapshot!=phoneSnapshot{t.Fatalf("phone snapshot was not stored: %#v",response)}

	pcSnapshot:=`{"corex.notes":"[{\"title\":\"Edited on PC\"}]"}`
	a.mu.Lock();a.backupLocked();a.state.Snapshot=pcSnapshot;a.state.Revision++;_ = a.saveLocked();a.mu.Unlock()
	response=exchangeForTest(t,a,deviceID,peerKey,1,phoneSnapshot,"request-two")
	if response.Revision!=2||response.Snapshot!=pcSnapshot||!response.Changed{t.Fatalf("PC edit was not returned: %#v",response)}
}

type testExchangeResponse struct{Revision int64 `json:"revision"`;Snapshot string `json:"snapshot"`;Changed bool `json:"changed"`}

func exchangeForTest(t *testing.T,a *app,deviceID string,key []byte,revision int64,snapshot,requestID string)testExchangeResponse{
	t.Helper();stamp:=time.Now().UnixMilli();plain,_:=json.Marshal(map[string]any{"revision":revision,"snapshot":snapshot,"snapshotHash":hashText(snapshot),"updatedAt":stamp,"forcePhoneSnapshot":revision==0})
	aad:=[]byte("POST|/api/v1/sync/exchange|"+strconv.FormatInt(stamp,10)+"|"+requestID);iv,ciphertext,err:=seal(key,plain,aad);if err!=nil{t.Fatal(err)}
	body,_:=json.Marshal(envelope{IV:base64.StdEncoding.EncodeToString(iv),Cipher:base64.StdEncoding.EncodeToString(ciphertext)})
	request:=httptest.NewRequest(http.MethodPost,"/api/v1/sync/exchange",strings.NewReader(string(body)));request.Header.Set("X-Corex-Device",deviceID);request.Header.Set("X-Corex-Time",strconv.FormatInt(stamp,10));request.Header.Set("X-Corex-Request",requestID)
	recorder:=httptest.NewRecorder();a.exchange(recorder,request);if recorder.Code!=http.StatusOK{t.Fatalf("exchange status %d: %s",recorder.Code,recorder.Body.String())}
	var responseEnvelope envelope;if err=json.Unmarshal(recorder.Body.Bytes(),&responseEnvelope);err!=nil{t.Fatal(err)}
	responseAAD:=[]byte("RESPONSE|/api/v1/sync/exchange|"+strconv.FormatInt(stamp,10)+"|"+requestID);responsePlain,err:=openEnvelope(key,responseEnvelope,responseAAD);if err!=nil{t.Fatal(err)}
	var response testExchangeResponse;if err=json.Unmarshal(responsePlain,&response);err!=nil{t.Fatal(err)};return response
}

func TestQRAndSnapshotValidation(t *testing.T){
	link:="corex://pair?host=192.168.1.20&port=47625&code=123456&server=0011223344556677"
	svg,err:=qrSVG(link);if err!=nil{t.Fatal(err)};if !strings.Contains(svg,"viewBox=\"0 0 45 45\"")||!strings.Contains(svg,"Corex pairing QR code"){t.Fatalf("unexpected QR SVG")}
	if !validSnapshot(`{"a":"b"}`)||validSnapshot(`[]`){t.Fatalf("snapshot validation failed")}
}

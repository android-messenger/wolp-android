// probe — the P1 stand backend. It stands in for KratosGate/Kratos/messenger in the local mTLS stand
// and records, for every request, which TLS identity it arrived with (P1-a, P1-e) and who sent it
// (WebView vs. a native HTTP client, via User-Agent and Sec-Fetch-Dest).
package main

import (
	"context"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io/fs"
	"log"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

//go:embed web
var webFS embed.FS

type entry struct {
	T       string `json:"t"`
	Host    string `json:"host"`
	Method  string `json:"method"`
	Path    string `json:"path"`
	Serial  string `json:"serial,omitempty"`
	Reused  string `json:"reused,omitempty"`
	Dest    string `json:"dest,omitempty"` // Sec-Fetch-Dest: document | empty | websocket | ...
	Agent   string `json:"agent"`          // webview | native | other
	Cookie  bool   `json:"cookie"`         // ory_kratos_session present
	Note    string `json:"note,omitempty"`
}

var (
	mu       sync.Mutex
	journal  []entry
	sessions = map[string]string{} // session token -> fake identity
	caCert   *x509.Certificate
	caKey    any
	host     = env("WOLP_HOST", "wolp.test")
)

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func agentOf(r *http.Request) string {
	ua := r.UserAgent()
	switch {
	case strings.Contains(ua, "wv)") || strings.Contains(ua, "Chrome/"):
		return "webview"
	case strings.HasPrefix(ua, "Dalvik/") || strings.HasPrefix(ua, "okhttp/") || strings.HasPrefix(ua, "WolpNative"):
		return "native"
	default:
		return "other:" + ua
	}
}

func record(r *http.Request, note string) entry {
	c, _ := r.Cookie("ory_kratos_session")
	e := entry{
		T: time.Now().UTC().Format("15:04:05.000"), Host: r.Host, Method: r.Method, Path: r.URL.RequestURI(),
		Serial: r.Header.Get("X-P1-Serial"), Reused: r.Header.Get("X-P1-Reused"),
		Dest: r.Header.Get("Sec-Fetch-Dest"), Agent: agentOf(r), Cookie: c != nil, Note: note,
	}
	mu.Lock()
	journal = append(journal, e)
	if len(journal) > 2000 {
		journal = journal[len(journal)-2000:]
	}
	mu.Unlock()
	b, _ := json.Marshal(e)
	log.Println(string(b))
	return e
}

func isApp(r *http.Request) bool    { return strings.HasPrefix(r.Host, "app.") }
func isEnroll(r *http.Request) bool { return strings.HasPrefix(r.Host, "enroll.") }

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func sessionOK(r *http.Request) bool {
	c, err := r.Cookie("ory_kratos_session")
	if err != nil {
		return false
	}
	mu.Lock()
	defer mu.Unlock()
	_, ok := sessions[c.Value]
	return ok
}

// ---- handlers ----

func login(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST", http.StatusMethodNotAllowed)
		return
	}
	tok := randHex(16)
	mu.Lock()
	sessions[tok] = "p1-user"
	mu.Unlock()
	// Kratos-like: HttpOnly, Secure, shared parent domain so the session works on app. and enroll. (D12, P1-c)
	http.SetCookie(w, &http.Cookie{Name: "ory_kratos_session", Value: tok, Domain: host, Path: "/",
		HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode, MaxAge: 86400})
	record(r, "login: session issued")
	writeJSON(w, 200, map[string]string{"identity": "p1-user"})
}

func logout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie("ory_kratos_session"); err == nil {
		mu.Lock()
		delete(sessions, c.Value)
		mu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: "ory_kratos_session", Value: "", Domain: host, Path: "/", MaxAge: -1, HttpOnly: true, Secure: true})
	record(r, "logout")
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func whoami(w http.ResponseWriter, r *http.Request) {
	ok := sessionOK(r)
	record(r, fmt.Sprintf("whoami=%v", ok))
	if !ok {
		writeJSON(w, 401, map[string]string{"error": "no session"})
		return
	}
	writeJSON(w, 200, map[string]string{"identity": "p1-user"})
}

// enroll: {spki: base64(DER SubjectPublicKeyInfo), ttlSec} -> {serial, chain:[pem]}. Stand-in for S3 enroll/finish.
func enroll(w http.ResponseWriter, r *http.Request) {
	var req struct {
		SPKI   string `json:"spki"`
		TTLSec int    `json:"ttlSec"`
		Label  string `json:"label"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, map[string]string{"error": err.Error()})
		return
	}
	der, err := base64.StdEncoding.DecodeString(req.SPKI)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "spki b64"})
		return
	}
	pub, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		writeJSON(w, 400, map[string]string{"error": "spki: " + err.Error()})
		return
	}
	if req.TTLSec <= 0 {
		req.TTLSec = 72 * 3600
	}
	sn, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 63))
	now := time.Now()
	tpl := &x509.Certificate{
		SerialNumber: sn,
		Subject:      pkix.Name{CommonName: "p1-device " + req.Label, Organization: []string{"wolp-p1"}},
		NotBefore:    now.Add(-5 * time.Minute),
		NotAfter:     now.Add(time.Duration(req.TTLSec) * time.Second),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	certDER, err := x509.CreateCertificate(rand.Reader, tpl, caCert, pub, caKey)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	leaf := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER}))
	ca := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caCert.Raw}))
	serial := strings.ToUpper(fmt.Sprintf("%X", sn)) // nginx $ssl_client_serial format
	record(r, "enroll: issued serial "+serial)
	writeJSON(w, 200, map[string]any{"serial": serial, "chain": []string{leaf, ca}, "notAfter": tpl.NotAfter.UTC()})
}

func echo(w http.ResponseWriter, r *http.Request) {
	e := record(r, r.URL.Query().Get("via"))
	writeJSON(w, 200, e)
}

func ping(w http.ResponseWriter, r *http.Request) {
	e := record(r, "device/ping")
	writeJSON(w, 200, map[string]string{"serial": e.Serial, "serverTime": time.Now().UTC().Format(time.RFC3339Nano)})
}

// ws: sends the TLS identity it was opened with every 2 s; lets P1-e see whether an open WS keeps the old cert.
func ws(w http.ResponseWriter, r *http.Request) {
	e := record(r, "ws: open")
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		return
	}
	defer c.CloseNow()
	id := randHex(4)
	opened := time.Now()
	ctx := c.CloseRead(context.Background())
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		msg, _ := json.Marshal(map[string]any{"conn": id, "serial": e.Serial, "agent": e.Agent, "ageSec": int(time.Since(opened).Seconds())})
		wctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		err := c.Write(wctx, websocket.MessageText, msg)
		cancel()
		if err != nil {
			log.Printf(`{"ws":"%s","closed":"%v","ageSec":%d}`, id, err, int(time.Since(opened).Seconds()))
			return
		}
		select {
		case <-ctx.Done():
			log.Printf(`{"ws":"%s","closed":"client","ageSec":%d}`, id, int(time.Since(opened).Seconds()))
			return
		case <-t.C:
		}
	}
}

func journalH(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	out := make([]entry, len(journal))
	copy(out, journal)
	if r.URL.Query().Get("clear") == "1" {
		journal = nil
	}
	mu.Unlock()
	writeJSON(w, 200, out)
}

func static(sub string) http.Handler {
	f, _ := fs.Sub(webFS, "web")
	files := http.FileServer(http.FS(f))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		record(r, "")
		p := strings.TrimPrefix(r.URL.Path, "/messenger-ui")
		if p == "" || p == "/" || !strings.Contains(p[strings.LastIndex(p, "/"):], ".") {
			p = "/" + sub // SPA fallback
		}
		w.Header().Set("Cache-Control", "no-store")
		r2 := r.Clone(r.Context())
		r2.URL.Path = p
		files.ServeHTTP(w, r2)
	})
}

func main() {
	caPEM, err := os.ReadFile(env("DEVICE_CA_CERT", "../pki/out/device-ca.pem"))
	if err != nil {
		log.Fatal(err)
	}
	keyPEM, err := os.ReadFile(env("DEVICE_CA_KEY", "../pki/out/device-ca.key"))
	if err != nil {
		log.Fatal(err)
	}
	b, _ := pem.Decode(caPEM)
	if caCert, err = x509.ParseCertificate(b.Bytes); err != nil {
		log.Fatal(err)
	}
	kb, _ := pem.Decode(keyPEM)
	if caKey, err = x509.ParsePKCS8PrivateKey(kb.Bytes); err != nil {
		if caKey, err = x509.ParseECPrivateKey(kb.Bytes); err != nil {
			log.Fatal(err)
		}
	}

	appUI, enrollUI := static("probe.html"), static("login.html")
	mux := http.NewServeMux()
	mux.HandleFunc("/p1/login", login)
	mux.HandleFunc("/p1/logout", logout)
	mux.HandleFunc("/p1/echo", echo)
	mux.HandleFunc("/p1/ws", ws)
	mux.HandleFunc("/p1/journal", journalH)
	mux.HandleFunc("/.ory/kratos/public/sessions/whoami", whoami)
	mux.HandleFunc("/v1/device/ping", ping)
	mux.HandleFunc("POST /p1/enroll", func(w http.ResponseWriter, r *http.Request) {
		if !isEnroll(r) {
			http.NotFound(w, r)
			return
		}
		enroll(w, r)
	})
	mux.HandleFunc("/messenger-ui/", func(w http.ResponseWriter, r *http.Request) {
		if isApp(r) {
			appUI.ServeHTTP(w, r)
		} else {
			enrollUI.ServeHTTP(w, r)
		}
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		record(r, "404")
		http.NotFound(w, r)
	})
	log.Printf("probe: host=%s listening :8080", host)
	log.Fatal(http.ListenAndServe(":8080", mux))
}

package com.example.photonclone

// Note: no dollar signs / template literals in the JS, because this is a Kotlin raw string.
val WEB_UI = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Photon Clone Remote</title>
<style>
body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;display:flex;flex-direction:column;height:100vh}
#v{flex:1;min-height:0;width:100%;object-fit:contain;background:#000}
#c{padding:12px 16px;display:grid;grid-template-columns:150px 1fr 90px;gap:8px 12px;align-items:center;background:#1b1b1b}
input[type=range]{width:100%}
.val{text-align:right;font-variant-numeric:tabular-nums}
#msg{color:#fc6;font-size:13px;padding:0 16px 10px;background:#1b1b1b}
</style></head><body>
<img id="v" alt="live view">
<div id="c">
 <label><input type="checkbox" id="man"> Manual exposure</label><span></span><span></span>
 <label>ISO</label><input type="range" id="iso" min="0" value="0"><span class="val" id="isoV"></span>
 <label>Shutter</label><input type="range" id="sh" min="0" value="0"><span class="val" id="shV"></span>
 <label><input type="checkbox" id="mf"> Manual focus</label><span></span><span></span>
 <label>Focus (far - near)</label><input type="range" id="fo" min="0" max="1000" value="0"><span class="val" id="foV"></span>
</div>
<div id="msg">Live view is a low-latency preview. Very long shutter speeds update it slowly; the phone's photos use the full exposure.</div>
<script>
var K = new URLSearchParams(location.search).get('key') || '';
var S = null, drag = {}, timer = {};
function g(id){ return document.getElementById(id); }
function shutterText(ns){ return ns < 5e8 ? '1/' + Math.round(1e9/ns) : (ns/1e9).toFixed(1) + 's'; }
function nearestIdx(arr, v){ var b = 0; for (var i = 0; i < arr.length; i++) if (Math.abs(arr[i]-v) < Math.abs(arr[b]-v)) b = i; return b; }
function api(p){
  var q = 'key=' + encodeURIComponent(K);
  for (var k in p) q += '&' + k + '=' + encodeURIComponent(p[k]);
  return fetch('/api/set?' + q).then(function(r){ return r.json(); }).then(render).catch(function(){});
}
function send(id, p){ clearTimeout(timer[id]); timer[id] = setTimeout(function(){ api(p); }, 80); }
function render(s){
  S = s;
  g('man').checked = s.manual; g('mf').checked = s.manualFocus;
  g('iso').disabled = !s.manual; g('sh').disabled = !s.manual;
  g('fo').disabled = !(s.focusMax > 0);
  if (!drag.iso){ g('iso').max = s.isoStops.length - 1; g('iso').value = nearestIdx(s.isoStops, s.iso); }
  if (!drag.sh){ g('sh').max = s.shutterStops.length - 1; g('sh').value = nearestIdx(s.shutterStops, s.shutterNs); }
  if (!drag.fo && s.focusMax > 0){ g('fo').value = Math.round(s.focus / s.focusMax * 1000); }
  g('isoV').textContent = s.isoStops[+g('iso').value] || s.iso;
  g('shV').textContent = shutterText(s.shutterStops[+g('sh').value] || s.shutterNs);
  var d = s.focusMax > 0 ? +g('fo').value / 1000 * s.focusMax : 0;
  g('foV').textContent = s.focusMax <= 0 ? 'fixed' : (d < 0.05 ? 'inf' : (1/d).toFixed(2) + ' m');
}
g('man').onchange = function(){ api({manual: this.checked ? 1 : 0}); };
g('mf').onchange = function(){ api({mf: this.checked ? 1 : 0}); };
g('iso').oninput = function(){ g('isoV').textContent = S.isoStops[+this.value]; send('iso', {iso: S.isoStops[+this.value]}); };
g('sh').oninput = function(){ g('shV').textContent = shutterText(S.shutterStops[+this.value]); send('sh', {shutter: S.shutterStops[+this.value]}); };
g('fo').oninput = function(){ send('fo', {focus: (+this.value / 1000 * S.focusMax).toFixed(3)}); };
['iso','sh','fo'].forEach(function(id){
  g(id).onpointerdown = function(){ drag[id] = true; };
  g(id).onpointerup = function(){ drag[id] = false; };
});
function poll(){ fetch('/api/state?key=' + encodeURIComponent(K)).then(function(r){ return r.json(); }).then(render).catch(function(){}); }
function startStream(){ g('v').src = '/stream?key=' + encodeURIComponent(K) + '&t=' + Date.now(); }
g('v').onerror = function(){ setTimeout(startStream, 2000); };
startStream(); poll(); setInterval(poll, 1000);
</script></body></html>
""".trimIndent()

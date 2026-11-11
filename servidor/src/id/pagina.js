// Tela de login unificada do Vetorial ID (Vênus, Vindex, Vortex). Serve em GET /id.
const esc = (s) => String(s).replace(/[<>&]/g, (c) => ({ "<": "\\u003c", ">": "\\u003e", "&": "\\u0026" }[c]));

export function paginaDeLogin(origens) {
  const cfg = esc(JSON.stringify({ origens }));
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#08080F"><title>Vetorial ID</title>
<style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;min-height:100dvh;display:grid;place-items:center;padding:20px;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#f4f4fb;background:radial-gradient(900px 500px at 20% -10%,#3a1d5c55,transparent),radial-gradient(700px 500px at 100% 110%,#1d3a5c55,transparent),#08080F}
main{width:100%;max-width:400px;padding:28px;border-radius:22px;background:#ffffff0d;border:1px solid #ffffff1f;backdrop-filter:blur(18px)}
h1{margin:0 0 4px;font-size:22px}p.s{margin:0 0 18px;color:#b9b9d0;font-size:14px}
.abas{display:flex;gap:6px;background:#0006;padding:4px;border-radius:12px;margin-bottom:16px}
.abas button{flex:1;border:0;border-radius:9px;padding:9px;background:none;color:#b9b9d0;font-weight:600}
.abas button[aria-selected=true]{background:#ffffff1c;color:#fff}
label{display:block;font-size:13px;color:#b9b9d0;margin:12px 0 4px}
input{width:100%;padding:12px 14px;border-radius:12px;border:1px solid #ffffff2a;background:#0008;color:#fff;font-size:16px}
.go{width:100%;margin-top:18px;padding:13px;border:0;border-radius:12px;font-weight:700;font-size:16px;color:#08080F;background:linear-gradient(90deg,#EC6FD6,#7aa2ff)}
.go:disabled{opacity:.6}#msg{min-height:20px;margin-top:12px;font-size:14px;color:#ff9db0}#msg.ok{color:#8ff0b4}
.aviso{margin-top:14px;padding:14px;border-radius:14px;background:#ffffff10;border:1px solid #ffffff22;font-size:14px}
.aviso h2{margin:0 0 8px;font-size:15px}.aviso ul{list-style:none;margin:0 0 10px;padding:0}
.aviso li{display:flex;justify-content:space-between;gap:8px;align-items:center;padding:8px 0;border-bottom:1px solid #ffffff14}
.aviso button{border:1px solid #ffffff33;background:#ffffff14;color:#fff;border-radius:10px;padding:7px 10px}
.aviso .cancel{width:100%;margin-top:6px}
.rod{margin-top:16px;text-align:center;font-size:12px;color:#8d8da8}
</style></head><body><main>
<h1>Vetorial ID</h1><p class="s">Uma conta só para Vênus, Vindex e Vortex.</p>
<div class="abas" role="tablist"><button id="tEntrar" role="tab" aria-selected="true">Entrar</button><button id="tCriar" role="tab" aria-selected="false">Criar conta</button></div>
<form id="f" autocomplete="on">
<div id="cNome" hidden><label for="nome">Nome da loja ou seu nome</label><input id="nome" autocomplete="organization"></div>
<label for="email">E-mail</label><input id="email" type="email" required autocomplete="username">
<label for="senha">Senha</label><input id="senha" type="password" required minlength="8" autocomplete="current-password">
<button class="go" id="ok" type="submit">Entrar</button></form>
<div id="msg" role="status" aria-live="polite"></div><div id="extra"></div>
<div class="rod">Até 3 aparelhos vinculados, 2 usando ao mesmo tempo.</div></main>
<script>
const CFG=${cfg};
const $=(i)=>document.getElementById(i);
const q=new URLSearchParams(location.search);
let criar=false,pendente=null;
function aba(c){criar=c;$("tEntrar").setAttribute("aria-selected",!c);$("tCriar").setAttribute("aria-selected",c);$("cNome").hidden=!c;$("ok").textContent=c?"Criar conta":"Entrar";$("senha").autocomplete=c?"new-password":"current-password";}
$("tEntrar").onclick=()=>aba(false);$("tCriar").onclick=()=>aba(true);
function aparelho(){let id="";try{id=localStorage.getItem("vid_aparelho")||"";if(!id){id=crypto.randomUUID();localStorage.setItem("vid_aparelho",id);}}catch(e){id=crypto.randomUUID();}
const ua=navigator.userAgent;const ios=/iPhone|iPad/.test(ua);const win=/Windows/.test(ua);const and=/Android/.test(ua);
const plataforma=q.get("plataforma")||(ios?"ios":and?"android":win?"windows":"web");
return{id,plataforma,nome:q.get("nome")||(ios?"iPhone":and?"Android":win?"Windows":"Navegador")};}
function msg(t,ok){$("msg").textContent=t||"";$("msg").className=ok?"ok":"";}
function destinoOk(u){try{const x=new URL(u);return x.origin===location.origin||CFG.origens.includes(x.origin)||x.hostname==="vetorial.tech"||x.hostname.endsWith(".vetorial.tech");}catch(e){return false;}}
async function pronto(r){try{localStorage.setItem("vid_token",r.token);}catch(e){}
const v=q.get("voltar");let t=r.token;const app=q.get("app");
if(app){try{const a=await fetch("/id/app-token",{method:"POST",headers:{"content-type":"application/json",authorization:"Bearer "+r.token},body:JSON.stringify({app})});if(a.ok)t=(await a.json()).token;}catch(e){}}
if(v&&destinoOk(v)){location.href=v+(v.includes("#")?"&":"#")+"vid="+encodeURIComponent(t);return;}
if(window.opener){try{window.opener.postMessage({vetorialId:r.token},"*");}catch(e){}}
msg("Pronto! Você já pode voltar ao app.",true);}
function listaAviso(titulo,itens,rotulo,acao){const e=$("extra");e.textContent="";const d=document.createElement("div");d.className="aviso";
const h=document.createElement("h2");h.textContent=titulo;d.appendChild(h);const ul=document.createElement("ul");
for(const it of itens){const li=document.createElement("li");const s=document.createElement("span");s.textContent=it.nome+" ("+it.plataforma+")";const b=document.createElement("button");b.textContent=rotulo;b.onclick=()=>acao(it);li.append(s,b);ul.appendChild(li);}
d.appendChild(ul);const c=document.createElement("button");c.className="cancel";c.textContent="Cancelar login neste aparelho";c.onclick=()=>{e.textContent="";msg("Login cancelado.");};d.appendChild(c);e.appendChild(d);}
async function enviar(extra){msg("");$("ok").disabled=true;
try{const corpo={email:$("email").value,senha:$("senha").value,nome:$("nome").value,dispositivo:aparelho(),...extra};
const r=await fetch(criar&&!extra?"/id/cadastrar":"/id/entrar",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(corpo)});
const d=await r.json();
if(r.ok){$("extra").textContent="";pronto(d);}
else if(d.codigo==="limite_simultaneo"){listaAviso("Já há 2 aparelhos usando a conta agora. Encerre um para entrar aqui:",d.ativas.map(a=>({nome:a.nome,plataforma:a.plataforma,id:a.sessao})),"Encerrar",(a)=>{criar=false;enviar({encerrar:a.id});});}
else if(d.codigo==="limite_dispositivos"){listaAviso("Você já tem 3 aparelhos vinculados. Remova um para vincular este:",d.dispositivos,"Remover",(a)=>{criar=false;enviar({remover:a.id});});}
else msg(d.error||"Não deu certo. Tente de novo.");
}catch(e){msg("Sem conexão. Tente de novo.");}
$("ok").disabled=false;}
$("f").onsubmit=(e)=>{e.preventDefault();enviar({});};
</script></body></html>`;
}

/** Entrada única: se este aparelho já tem login, entrega o token do app sem pedir a senha; senão manda para o login. */
export function paginaSso(origens) {
  const cfg = esc(JSON.stringify({ origens }));
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Vetorial ID</title>
<body style="font-family:system-ui;background:#08080F;color:#f4f4fb;display:grid;place-items:center;min-height:100dvh;margin:0"><p id="m">Entrando com o Vetorial ID…</p>
<script>
const CFG=${cfg};const q=new URLSearchParams(location.search);const v=q.get("voltar")||"";const app=q.get("app")||"";
function ok(u){try{const x=new URL(u);return x.origin===location.origin||CFG.origens.includes(x.origin)||x.hostname==="vetorial.tech"||x.hostname.endsWith(".vetorial.tech");}catch(e){return false;}}
(async()=>{
let t="";try{t=localStorage.getItem("vid_token")||"";}catch(e){}
const login=()=>{location.replace("/id?"+q.toString());};
if(!t||!app||!ok(v))return login();
try{const a=await fetch("/id/app-token",{method:"POST",headers:{"content-type":"application/json",authorization:"Bearer "+t},body:JSON.stringify({app})});
if(!a.ok)return login();const d=await a.json();location.replace(v+(v.includes("#")?"&":"#")+"vid="+encodeURIComponent(d.token));}catch(e){document.getElementById("m").textContent="Sem conexão. Tente de novo.";}
})();
</script>`;
}

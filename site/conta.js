(() => {
  const $ = (id) => document.getElementById(id);
  const A = window.VENUS;
  const guardar = (t) => { try { sessionStorage.setItem("vid", t); } catch (e) {} };
  const ler = () => { try { return sessionStorage.getItem("vid") || ""; } catch (e) { return ""; } };
  const apagar = () => { try { sessionStorage.removeItem("vid"); } catch (e) {} };

  // o Vetorial ID devolve o login em #vid=…; tiramos da barra de endereço na hora
  const m = /[#&]vid=([^&]+)/.exec(location.hash);
  if (m) { guardar(decodeURIComponent(m[1])); history.replaceState(null, "", location.pathname + location.search); }

  $("entrar").href = A.entrar("conta.html");
  $("dl-android").href = A.baixarAndroid;
  $("dl-windows").href = A.baixarWindows;

  const mostrar = (qual) => { $("carregando").hidden = true; $("deslogado").hidden = qual !== "d"; $("logado").hidden = qual !== "l"; $("sair").hidden = qual !== "l"; };
  async function api(caminho, token) {
    const r = await fetch(A.api + caminho, { headers: { authorization: "Bearer " + token } });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw Object.assign(new Error(j.error || j.mensagem || "Erro " + r.status), { status: r.status });
    return j;
  }

  function estado(l) {
    const e = $("estado");
    if (!l || l.plano === "sem_loja") { e.className = "aviso"; e.textContent = "Sua conta está pronta. Abra a Vênus e entre para começar o teste grátis."; return; }
    if (l.plano === "dono") { e.className = "aviso ok"; e.textContent = "Conta do dono da Vetorial Tech: acesso total, sem limites."; $("acoes").hidden = true; return; }
    if (l.emTeste) { e.className = "aviso ok"; e.innerHTML = `🎁 Você está nos <b>7 dias grátis</b>: faltam ${l.diasDeTesteRestantes} dia(s). Assine agora e a primeira cobrança só acontece quando o teste acabar.`; return; }
    if (l.acesso) { e.className = "aviso ok"; e.textContent = `Assinatura ativa. Faltam ${l.diasPagosRestantes} dia(s) pagos.`; $("assinar").textContent = "Renovar com o Mercado Pago"; return; }
    e.className = "aviso al"; e.textContent = "O seu período grátis acabou. Assine o Plano Fundadores para continuar usando.";
  }

  async function iniciar() {
    const t = ler();
    if (!t) return mostrar("d");
    try {
      const c = await api("/id/conta", t);
      const u = c.usuario || {};
      $("nome").textContent = u.nome || u.email || "Minha conta";
      $("email").textContent = u.email || "";
      $("inicial").textContent = String(u.nome || u.email || "V").trim().charAt(0).toUpperCase();
      estado(c.licenca);
      mostrar("l");
    } catch (e) {
      apagar();
      mostrar("d");
    }
  }

  $("assinar").addEventListener("click", async () => {
    const b = $("assinar"), msg = $("msg");
    b.disabled = true; msg.textContent = "Abrindo o Mercado Pago…";
    try {
      const r = await api("/id/licenca?gerar=1&gateway=mercadopago", ler());
      const url = r.cobranca && r.cobranca.url;
      if (!url) throw new Error("O Mercado Pago não devolveu o link.");
      location.href = url;
    } catch (e) {
      msg.textContent = e.message || "Não consegui abrir o pagamento. Tente de novo.";
      b.disabled = false;
    }
  });
  $("sair").addEventListener("click", (ev) => { ev.preventDefault(); apagar(); mostrar("d"); });
  iniciar();
})();

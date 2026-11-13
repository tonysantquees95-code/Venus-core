import test from "node:test";
import assert from "node:assert/strict";
import nacl from "../src/vendor/nacl.js";
import { avaliar, lerSelo } from "../src/id/trava.ts";

const par = nacl.sign.keyPair();
const b64u = (b) => Buffer.from(b).toString("base64url");
const chave = b64u(par.publicKey);
const DIA = 86_400_000;
function selo(c, k = par.secretKey) {
  const p = b64u(Buffer.from(JSON.stringify({ v: 1, u: "u1", em: "a@b.c", d: "", ac: true, teste: true, fim: 0, iat: 0, exp: 0, ...c })));
  return `${p}.${b64u(nacl.sign.detached(Buffer.from(p), k))}`;
}
const T = 1_800_000_000_000;

test("selo legítimo dentro do teste libera e conta os dias", () => {
  const e = avaliar(selo({ iat: T, exp: T + 3 * DIA, fim: T + 5 * DIA }), T + 1000, T, "u1", chave);
  assert.equal(e.tipo, "livre");
  assert.equal(e.diasRestantes, 5);
});
test("selo adulterado (fim esticado) ou de outra chave é recusado", () => {
  const bom = selo({ iat: T, exp: T + DIA, fim: T + DIA });
  const [p, a] = bom.split(".");
  const falso = JSON.parse(Buffer.from(p, "base64url").toString()); falso.fim += 365 * DIA;
  assert.equal(lerSelo(`${b64u(Buffer.from(JSON.stringify(falso)))}.${a}`, chave), null);
  assert.equal(avaliar(selo({ iat: T, exp: T + DIA }, nacl.sign.keyPair().secretKey), T, T, "u1", chave).tipo, "validar_online");
  assert.equal(avaliar("lixo", T, T, "u1", chave).tipo, "validar_online");
  assert.equal(avaliar(undefined, T, T, "u1", chave).motivo, "sem_selo");
});
test("selo de outra conta não vale", () => {
  assert.equal(avaliar(selo({ u: "outro", iat: T, exp: T + DIA, fim: T + DIA }), T, T, "u1", chave).motivo, "invalido");
});
test("depois do fim do teste, mesmo offline e com selo ainda na validade, bloqueia", () => {
  const s = selo({ iat: T, exp: T + 3 * DIA, fim: T + DIA });
  assert.equal(avaliar(s, T + DIA + 1, T, "u1", chave).tipo, "teste_acabou");
});
test("selo com acesso falso bloqueia", () => {
  assert.equal(avaliar(selo({ ac: false, iat: T, exp: T + DIA }), T, T, "u1", chave).tipo, "teste_acabou");
});
test("muito tempo offline pede validação online; relógio voltado também", () => {
  const s = selo({ iat: T, exp: T + 3 * DIA, fim: T + 30 * DIA });
  assert.equal(avaliar(s, T + 4 * DIA, T, "u1", chave).motivo, "vencido");
  assert.equal(avaliar(s, T + DIA, T + 2 * DIA, "u1", chave).motivo, "relogio");
});

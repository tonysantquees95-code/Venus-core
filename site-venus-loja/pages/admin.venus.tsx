// RASCUNHO: confirmar amanhã com pages/admin.pedidos.tsx como as outras páginas do painel
// checam o administrador e mostram o menu (AdminNav), e copiar o mesmo padrão.
import React from "react";
import { Helmet } from "react-helmet";
import { VenusLoja } from "../components/VenusLoja";
import styles from "./admin.venus.module.css";

export default function AdminVenusPage() {
  return (
    <>
      <Helmet>
        <title>Vênus da Loja</title>
        <meta name="apple-mobile-web-app-title" content="Vênus Loja" />
      </Helmet>
      <div className={styles.pagina}>
        <VenusLoja />
      </div>
    </>
  );
}

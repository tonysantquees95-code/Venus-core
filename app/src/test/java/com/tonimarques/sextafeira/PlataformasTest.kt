package com.tonimarques.sextafeira

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlataformasTest {
    @Test fun conversoes() {
        assertEquals(1290, Plataformas.centavos("12.90"))
        assertEquals(1290, Plataformas.centavos("12,90"))
        assertEquals(1290, Plataformas.centavos(12.9))
        assertNull(Plataformas.centavos(""))
        assertNull(Plataformas.centavos(JSONObject.NULL))
        assertEquals("12.90", Plataformas.reais(1290))
        assertEquals(1762349400000L, Plataformas.data("2025-11-05T13:30:00Z"))
        assertEquals(1762349400000L, Plataformas.data("2025-11-05T10:30:00-03:00"))
        assertEquals(1762349400000L, Plataformas.data("2025-11-05T13:30:00+0000"))
        assertTrue(Plataformas.ehRetirada("Retirar na loja"))
        assertTrue(Plataformas.idCurto("8123456789012") > 0)
    }

    @Test fun wooCommerce() {
        val produtos = Plataformas.lerProdutosWoo(JSONArray("""[
            {"id":10,"name":"Gel morango","status":"publish","manage_stock":true,"stock_quantity":3,"price":"19.90","regular_price":"24.90","sale_price":"19.90","permalink":"https://loja.com/p/gel"},
            {"id":11,"name":"Óleo","status":"draft","manage_stock":false,"stock_quantity":null,"price":"30","regular_price":"30","sale_price":""}]"""))
        assertEquals(1990, produtos[0].precoCentavos)
        assertEquals(2490, produtos[0].precoAntigo)
        assertEquals(3, produtos[0].estoque)
        assertEquals("https://loja.com/p/gel", produtos[0].link)
        assertEquals(false, produtos[1].ativo)
        assertNull(produtos[1].estoque)
        val pedidos = Plataformas.lerPedidosWoo(JSONArray("""[{"id":55,"status":"processing","total":"49.80","discount_total":"0.00","shipping_total":"10.00",
            "date_created_gmt":"2025-11-05T13:30:00","date_paid_gmt":"2025-11-05T13:35:00","billing":{"first_name":"Ana","last_name":"Lima","phone":"51999"},
            "shipping_lines":[{"method_id":"local_pickup","method_title":"Retirada"}],"line_items":[{"name":"Gel morango","quantity":2,"price":19.9,"product_id":10}]}]"""))
        val o = pedidos.single()
        assertEquals("paid", o.status)
        assertEquals(4980, o.totalCentavos)
        assertEquals("Ana Lima", o.cliente)
        assertEquals("pickup", o.entrega)
        assertEquals(1762349400000L, o.criado)
        assertEquals(1990, o.itens.single().precoCentavos)
        assertEquals("completed", Plataformas.statusParaWoo("delivered"))
        assertEquals("19.90", Plataformas.corpoWoo(produtos[0].copy(precoAntigo = null, precoCentavos = 2490), "promocao", 1990).getString("sale_price"))
    }

    @Test fun nuvemshop() {
        val produtos = Plataformas.lerProdutosNuvem(JSONArray("""[{"id":7,"name":{"pt":"Vibrador Bullet","es":"Bala"},"published":true,"canonical_url":"https://loja.lojavirtualnuvem.com.br/produtos/bullet",
            "variants":[{"id":70,"price":"89.90","promotional_price":"79.90","stock":2},{"id":71,"price":"89.90","promotional_price":null,"stock":1}]},
            {"id":8,"name":{"pt":"Gel"},"published":false,"variants":[{"id":80,"price":"20.00","promotional_price":null,"stock":null}]}]"""))
        assertEquals("Vibrador Bullet", produtos[0].nome)
        assertEquals(7990, produtos[0].precoCentavos)
        assertEquals(8990, produtos[0].precoAntigo)
        assertEquals(3, produtos[0].estoque)
        assertNull(produtos[1].estoque)
        val pedidos = Plataformas.lerPedidosNuvem(JSONArray("""[{"id":900,"status":"open","payment_status":"paid","shipping_status":"unpacked","total":"89.90",
            "discount":"0.00","shipping_cost_customer":"15.00","created_at":"2025-11-05T13:30:00+0000","paid_at":"2025-11-05T13:40:00+0000",
            "contact_name":"Bia","contact_phone":"51988","shipping_pickup_type":"ship","products":[{"name":"Vibrador Bullet","quantity":1,"price":"74.90","product_id":7}]},
            {"id":901,"status":"closed","payment_status":"paid","shipping_status":"shipped","total":"10"}]"""))
        assertEquals("paid", pedidos[0].status)
        assertEquals(1500, pedidos[0].freteCentavos)
        assertEquals("shipping", pedidos[0].entrega)
        assertEquals("delivered", pedidos[1].status)
    }

    @Test fun shopify() {
        val produtos = Plataformas.lerProdutosShopify(JSONObject("""{"products":{"nodes":[{"id":"gid://shopify/Product/8123456789012","legacyResourceId":"8123456789012",
            "title":"Lingerie Renda","status":"ACTIVE","onlineStoreUrl":"https://loja.myshopify.com/products/renda",
            "variants":{"nodes":[{"id":"gid://shopify/ProductVariant/1","price":"99.90","compareAtPrice":"129.90","inventoryQuantity":4,"inventoryItem":{"id":"gid://shopify/InventoryItem/1","tracked":true}}]}}]}}"""))
        val p = produtos.single()
        assertEquals(9990, p.precoCentavos)
        assertEquals(12990, p.precoAntigo)
        assertEquals(4, p.estoque)
        assertTrue(p.ativo)
        val pedidos = Plataformas.lerPedidosShopify(JSONObject("""{"orders":{"nodes":[{"legacyResourceId":"555","name":"#1001","createdAt":"2025-11-05T13:30:00Z",
            "processedAt":"2025-11-05T13:30:00Z","cancelledAt":null,"displayFinancialStatus":"PAID","displayFulfillmentStatus":"UNFULFILLED",
            "customer":{"displayName":"Carla","phone":null},"totalPriceSet":{"shopMoney":{"amount":"99.9"}},"totalDiscountsSet":{"shopMoney":{"amount":"0.0"}},
            "totalShippingPriceSet":{"shopMoney":{"amount":"0.0"}},"shippingLine":{"title":"Retirada na loja"},
            "lineItems":{"nodes":[{"title":"Lingerie Renda","quantity":1,"originalUnitPriceSet":{"shopMoney":{"amount":"99.9"}},"product":{"legacyResourceId":"8123456789012"}}]}}]}}"""))
        val o = pedidos.single()
        assertEquals(1001, o.id)
        assertEquals("paid", o.status)
        assertEquals("pickup", o.entrega)
        assertEquals("Carla", o.cliente)
        assertEquals("", o.telefone)
        assertEquals(p.id, o.itens.single().produtoId)
    }
}

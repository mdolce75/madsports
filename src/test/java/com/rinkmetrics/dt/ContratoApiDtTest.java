package com.rinkmetrics.dt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.rinkmetrics.account.Perfil;
import com.rinkmetrics.account.SuscripcionTypeRepository;
import com.rinkmetrics.account.Usuario;
import com.rinkmetrics.account.UsuarioRepository;
import com.rinkmetrics.catalog.RosterService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * El servidor contra el contrato de la API de RinkMetrics ({@code src/test/resources/dt/contrato-api.json}): los mismos
 * escenarios que corre la API simulada de la demo ({@code src/test/js/contrato-api.test.mjs}), así la demo y la
 * pantalla sin señal no se separan del servidor. Cada escenario arranca con la base vacía y el reloj de prueba.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:contrato-dt;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(DtControllerTest.Config.class)
class ContratoApiDtTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");
    private static final Pattern ENTERA = Pattern.compile("^\\{(\\w+)}$");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UsuarioRepository usuarios;
    @Autowired SuscripcionTypeRepository suscripciones;
    @Autowired PartidoDtRepository partidos;
    @Autowired AccionDtRepository acciones;
    @Autowired DtControllerTest.RelojDePrueba reloj;
    @MockitoBean RosterService roster;

    @TestFactory
    Stream<DynamicTest> escenarios() throws IOException {
        JsonNode contrato;
        try (InputStream in = new ClassPathResource("dt/contrato-api.json").getInputStream()) {
            contrato = json.readTree(in);
        }
        JsonNode inicio = contrato.get("inicio");
        return StreamSupport.stream(contrato.get("escenarios").spliterator(), false)
                .map(e -> DynamicTest.dynamicTest(e.get("nombre").asText(), () -> correr(inicio, e)));
    }

    private void correr(JsonNode inicio, JsonNode escenario) throws Exception {
        acciones.deleteAll();
        partidos.deleteAll();
        usuarios.deleteAll();
        reloj.ahora = Instant.parse(inicio.get("instante").asText());
        String nombre = inicio.get("usuario").asText();
        Usuario u = new Usuario(nombre, "x", nombre + "@example.com", "Ana", "Pérez", 74, inicio.get("club").asText(),
                "CAVS", EnumSet.of(Perfil.DTFPP), Instant.now());
        u.suscribir(suscripciones.findByCodigo(inicio.get("plan").asText()).orElseThrow(), LocalDate.of(2026, 9, 15));
        usuarios.save(u);

        Map<String, JsonNode> vars = new HashMap<>();
        int i = 0;
        for (JsonNode paso : escenario.get("pasos")) {
            i++;
            if (paso.has("avanzar")) {
                reloj.avanzar(paso.get("avanzar").asInt());
                continue;
            }
            String[] pedido = sustituir(paso.get("pedido"), vars).asText().split(" ");
            String donde = "paso " + i + " (" + pedido[0] + " " + pedido[1] + ")";
            MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(pedido[0]), pedido[1])
                    .with(user(nombre).authorities(() -> "DTFPP")).with(csrf());
            if (paso.has("cuerpo")) {
                req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sustituir(paso.get("cuerpo"), vars)));
            }
            MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
            String texto = res.getContentAsString(StandardCharsets.UTF_8);
            assertThat(res.getStatus()).as(donde + ": status (" + texto + ")").isEqualTo(paso.get("estado").asInt());
            JsonNode cuerpo = texto.isEmpty() ? null : json.readTree(texto);
            if (paso.has("detalle")) {
                assertThat(cuerpo.path("detail").asText()).as(donde + ": detail").isEqualTo(paso.get("detalle").asText());
            }
            if (paso.has("respuesta")) {
                contiene(sustituir(paso.get("respuesta"), vars), cuerpo, donde);
            }
            if (paso.has("guardar")) {
                paso.get("guardar").properties().forEach(g -> vars.put(g.getKey(), cuerpo.get(g.getValue().asText())));
            }
        }
    }

    /** Reemplaza {nombre} por lo guardado: un valor entero conserva su tipo; dentro de un texto, se concatena. */
    static JsonNode sustituir(JsonNode v, Map<String, JsonNode> vars) {
        if (v.isTextual()) {
            Matcher entera = ENTERA.matcher(v.asText());
            if (entera.matches() && vars.containsKey(entera.group(1))) {
                return vars.get(entera.group(1));
            }
            return TextNode.valueOf(VARIABLE.matcher(v.asText()).replaceAll(m -> vars.containsKey(m.group(1))
                    ? Matcher.quoteReplacement(vars.get(m.group(1)).asText()) : Matcher.quoteReplacement(m.group())));
        }
        if (v.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            v.forEach(x -> a.add(sustituir(x, vars)));
            return a;
        }
        if (v.isObject()) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            v.properties().forEach(e -> o.set(e.getKey(), sustituir(e.getValue(), vars)));
            return o;
        }
        return v;
    }

    /** esperado ⊆ real: los objetos, por las claves esperadas; las listas, completas y en orden. */
    static void contiene(JsonNode esperado, JsonNode real, String ruta) {
        if (esperado.isArray()) {
            assertThat(real != null && real.isArray()).as(ruta + ": se esperaba una lista y vino " + real).isTrue();
            assertThat(real.size()).as(ruta + ": largo de la lista (" + real + ")").isEqualTo(esperado.size());
            for (int k = 0; k < esperado.size(); k++) {
                contiene(esperado.get(k), real.get(k), ruta + "[" + k + "]");
            }
        } else if (esperado.isObject()) {
            assertThat(real != null && real.isObject()).as(ruta + ": se esperaba un objeto y vino " + real).isTrue();
            for (Iterator<Map.Entry<String, JsonNode>> it = esperado.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                assertThat(real.has(e.getKey())).as(ruta + "." + e.getKey() + ": no vino (" + real + ")").isTrue();
                contiene(e.getValue(), real.get(e.getKey()), ruta + "." + e.getKey());
            }
        } else if (esperado.isNumber()) {
            assertThat(real.isNumber() ? real.decimalValue().stripTrailingZeros() : real)
                    .as(ruta).isEqualTo(esperado.decimalValue().stripTrailingZeros());
        } else {
            assertThat(real).as(ruta).isEqualTo(esperado);
        }
    }
}

package com.madsports.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.madsports.account.Profile;
import com.madsports.account.SubscriptionPlanRepository;
import com.madsports.account.UserAccount;
import com.madsports.account.UserAccountRepository;
import com.madsports.catalog.RosterService;
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
 * El servidor contra el contrato de la API de RinkMetrics ({@code src/test/resources/coach/contract-api.json}): los mismos
 * escenarios que corre la API simulada de la demo ({@code src/test/js/contract-api.test.mjs}), así la demo y la
 * pantalla sin señal no se separan del servidor. Cada escenario arranca con la base vacía y el reloj de prueba.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:contrato-dt;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(CoachControllerTest.Config.class)
class CoachApiContractTest {

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");
    private static final Pattern WHOLE = Pattern.compile("^\\{(\\w+)}$");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired SubscriptionPlanRepository subscriptions;
    @Autowired LiveMatchRepository matches;
    @Autowired MatchActionRepository actions;
    @Autowired CoachControllerTest.TestClock clock;
    @MockitoBean RosterService roster;

    @TestFactory
    Stream<DynamicTest> scenarios() throws IOException {
        JsonNode contract;
        try (InputStream in = new ClassPathResource("dt/contrato-api.json").getInputStream()) {
            contract = json.readTree(in);
        }
        JsonNode home = contract.get("inicio");
        return StreamSupport.stream(contract.get("escenarios").spliterator(), false)
                .map(e -> DynamicTest.dynamicTest(e.get("nombre").asText(), () -> run(home, e)));
    }

    private void run(JsonNode home, JsonNode scenario) throws Exception {
        actions.deleteAll();
        matches.deleteAll();
        users.deleteAll();
        clock.now = Instant.parse(home.get("instante").asText());
        String name = home.get("usuario").asText();
        UserAccount u = new UserAccount(name, "x", name + "@example.com", "Ana", "Pérez", 74, home.get("club").asText(),
                "CAVS", EnumSet.of(Profile.COACHFPP), Instant.now());
        u.subscribe(subscriptions.findByCode(home.get("plan").asText()).orElseThrow(), LocalDate.of(2026, 9, 15));
        users.save(u);

        Map<String, JsonNode> vars = new HashMap<>();
        int i = 0;
        for (JsonNode step : scenario.get("pasos")) {
            i++;
            if (step.has("avanzar")) {
                clock.advance(step.get("avanzar").asInt());
                continue;
            }
            String[] request = replace(step.get("pedido"), vars).asText().split(" ");
            String where = "paso " + i + " (" + request[0] + " " + request[1] + ")";
            MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(request[0]), request[1])
                    .with(user(name).authorities(() -> "DTFPP")).with(csrf());
            if (step.has("cuerpo")) {
                req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(replace(step.get("cuerpo"), vars)));
            }
            MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
            String text = res.getContentAsString(StandardCharsets.UTF_8);
            assertThat(res.getStatus()).as(where + ": status (" + text + ")").isEqualTo(step.get("estado").asInt());
            JsonNode body = text.isEmpty() ? null : json.readTree(text);
            if (step.has("detalle")) {
                assertThat(body.path("detail").asText()).as(where + ": detail").isEqualTo(step.get("detalle").asText());
            }
            if (step.has("respuesta")) {
                contains(replace(step.get("respuesta"), vars), body, where);
            }
            if (step.has("guardar")) {
                step.get("guardar").properties().forEach(g -> vars.put(g.getKey(), body.get(g.getValue().asText())));
            }
        }
    }

    /** Reemplaza {nombre} por lo guardado: un valor entero conserva su tipo; dentro de un texto, se concatena. */
    static JsonNode replace(JsonNode v, Map<String, JsonNode> vars) {
        if (v.isTextual()) {
            Matcher whole = WHOLE.matcher(v.asText());
            if (whole.matches() && vars.containsKey(whole.group(1))) {
                return vars.get(whole.group(1));
            }
            return TextNode.valueOf(VARIABLE.matcher(v.asText()).replaceAll(m -> vars.containsKey(m.group(1))
                    ? Matcher.quoteReplacement(vars.get(m.group(1)).asText()) : Matcher.quoteReplacement(m.group())));
        }
        if (v.isArray()) {
            ArrayNode a = JsonNodeFactory.instance.arrayNode();
            v.forEach(x -> a.add(replace(x, vars)));
            return a;
        }
        if (v.isObject()) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            v.properties().forEach(e -> o.set(e.getKey(), replace(e.getValue(), vars)));
            return o;
        }
        return v;
    }

    /** esperado ⊆ real: los objetos, por las claves esperadas; las listas, completas y en orden. */
    static void contains(JsonNode expected, JsonNode real, String path) {
        if (expected.isArray()) {
            assertThat(real != null && real.isArray()).as(path + ": se esperaba una lista y vino " + real).isTrue();
            assertThat(real.size()).as(path + ": largo de la lista (" + real + ")").isEqualTo(expected.size());
            for (int k = 0; k < expected.size(); k++) {
                contains(expected.get(k), real.get(k), path + "[" + k + "]");
            }
        } else if (expected.isObject()) {
            assertThat(real != null && real.isObject()).as(path + ": se esperaba un objeto y vino " + real).isTrue();
            for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                assertThat(real.has(e.getKey())).as(path + "." + e.getKey() + ": no vino (" + real + ")").isTrue();
                contains(e.getValue(), real.get(e.getKey()), path + "." + e.getKey());
            }
        } else if (expected.isNumber()) {
            assertThat(real.isNumber() ? real.decimalValue().stripTrailingZeros() : real)
                    .as(path).isEqualTo(expected.decimalValue().stripTrailingZeros());
        } else {
            assertThat(real).as(path).isEqualTo(expected);
        }
    }
}

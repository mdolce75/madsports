# FPP Stats — Mad Sports

Aplicación de **Mad Sports** que genera un **informe estadístico en PDF** de un club de hockey sobre patines de la
Federación Porteña de Patín (FPP), con análisis redactado por IA (Claude).

**Filtros:** Club · Categoría (Infantil, Prejuvenil, Juvenil, Juniors, Reserva, Primera) · Rama (Masculino/Femenino)
· Período (Apertura, Clausura, Apertura + Clausura, Últimos 5 partidos). Si el club tiene equipo A y B en la
categoría y rama, cada uno tiene su sección.

En "Últimos 5 partidos", récord, resultados, rivales y el análisis por minuto usan solo esos partidos;
goleadores, pelota parada y disciplina son del torneo completo (SIDGAD no publica estadísticas por partido).

## Cómo funciona

```
Web (index.html) → POST /api/informes → job en segundo plano
  1. Extracción   SidgadClient: POST a server2.sidgad.es (cache + 400 ms entre pedidos)
  2. Cálculo      StatsEngine: todas las cifras, en código
  3. Análisis     ClaudeAnalyst: agente con herramientas de solo lectura → submit_analysis
                  (sin API key o ante cualquier falla: RuleBasedAnalyst)
  4. PDF          Thymeleaf (templates/report.html) → openhtmltopdf
```

La IA **no calcula números**: consulta las cifras ya calculadas con herramientas (`get_overview`,
`get_league_context`, `get_team_detail`) y devuelve el texto en un esquema validado. Los jugadores
destacados que no existen en los datos se descartan.

### Fuente de datos

`https://www.buenosaires.sidgad.com/league/697` carga fragmentos HTML de `https://www.server2.sidgad.es/cap/`
(requieren `Origin: https://www.buenosaires.sidgad.com`):

| Fragmento | Contenido |
|---|---|
| `cap_ls_1.php` | Catálogo de competiciones + equipos (`teams_array_<idc>`) |
| `cap_clasif_idc_<idc>_1.php` | Tabla de posiciones |
| `cap_cal_idc_<idc>_1.php` | Calendario y resultados |
| `cap_stats_1_<idc>.php` | Estadísticas por jugador |
| `cap_gr_<idp>_1.php` | Acta del partido: goles y tarjetas con período y minuto |

FPP = `id_fede_3`; la temporada activa es la de mayor `temp_N`. La tabla de posiciones de SIDGAD puede
quedar congelada (ej.: Apertura 2026 publicada el 03/05): el informe la muestra con su fecha y la marca
como desactualizada; el récord se calcula siempre desde el calendario.

### Estadísticas del informe

Ficha (PJ/G/E/P/GF/GC/puntos/efectividad), últimos 5, posiciones por torneo, local/visitante,
Apertura vs Clausura, comparativa de goles por partido contra la liga y el top 3, resultados y próximos
partidos, goleadores/asistidores con % de los goles del equipo, faltas directas y penales vs liga,
disciplina, cara a cara con cada rival, y **goles a favor/en contra y tarjetas propias/de los rivales
(amarilla, azul y roja por separado) por tramos de 5 minutos**, según las actas.

La duración de los períodos sale de `tpo_reg` en el catálogo (20' de Infantil a Juvenil, 25' de Juniors a
Primera). SIDGAD no publica el acta de todos los partidos: el informe indica cuántas actas se usaron sobre el
total de partidos jugados.

### Datos no publicados (404)

Si un fragmento de SIDGAD responde **404** (tabla, calendario, estadísticas o acta no publicados), el informe
**se genera igual** con lo disponible, sin error en la web, y el PDF lista al final qué datos faltaban. Un 404 no
se cachea (se reintenta en el próximo informe). Cualquier otro error (5xx, timeout) sí corta el informe.

## Correr

Requiere JDK 21.

```bash
export ANTHROPIC_API_KEY=...   # opcional: sin key se usa el análisis por reglas
./mvnw spring-boot:run
```

Abrir http://localhost:8080.

| Variable | Default | |
|---|---|---|
| `ANTHROPIC_API_KEY` | — | Habilita el análisis con Claude |
| `FPP_AI_MODEL` | `claude-opus-5-5` | Modelo |
| `FPP_AI_EFFORT` | `high` | `low`…`max` |
| `FPP_AI_ENABLED` | `true` | `false` fuerza el análisis por reglas |

Categorías relevadas: `fpp.sidgad.levels` en `application.yml`.

Cada análisis con Claude deja en el log el consumo real de tokens (`Consumo de Claude para ...`).

## API

- `GET /api/catalogo` — clubes con sus categorías y ramas
- `GET /api/ejemplo.pdf` — informe modelo con datos ficticios ("Club Ejemplo"), marcado en cada página; no consulta
  SIDGAD ni la API de Claude. Enlazado desde la web.
- `POST /api/informes` `{"clubId":74,"categoria":"INFANTIL","rama":"FEMENINO","periodo":"TEMPORADA"}` → `202 {id, estado}`
  (`periodo`: `APERTURA`, `CLAUSURA`, `TEMPORADA` o `ULTIMOS_5`)
- `GET /api/informes/{id}` — estado: `PENDIENTE`, `EXTRAYENDO`, `ANALIZANDO`, `GENERANDO_PDF`, `LISTO`, `ERROR`
- `GET /api/informes/{id}/pdf`

## Tests

```bash
./mvnw test
```

Los parsers y el motor se prueban contra HTML real guardado en `src/test/resources/fixtures`
(13/10/2026). Si SIDGAD cambia el markup, actualizar los fixtures y ver qué test falla.

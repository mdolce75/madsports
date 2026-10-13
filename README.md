# FPP Stats

Aplicación web que genera un **informe estadístico en PDF** de un club de hockey sobre patines de la
Federación Porteña de Patín (FPP), con análisis redactado por IA (Claude).

**Filtros:** Club · Categoría (Infantil, Prejuvenil, Juvenil, Juniors, Reserva, Primera) · Rama (Masculino/Femenino).
El informe cubre la temporada completa (Apertura + Clausura). Si el club tiene equipo A y B en la
categoría y rama, cada uno tiene su sección.

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

FPP = `id_fede_3`; la temporada activa es la de mayor `temp_N`. La tabla de posiciones de SIDGAD puede
quedar congelada (ej.: Apertura 2026 publicada el 03/05): el informe la muestra con su fecha y la marca
como desactualizada; el récord se calcula siempre desde el calendario.

### Estadísticas del informe

Ficha (PJ/G/E/P/GF/GC/puntos/efectividad), últimos 5, posiciones por torneo, local/visitante,
Apertura vs Clausura, comparativa de goles por partido contra la liga y el top 3, resultados y próximos
partidos, goleadores/asistidores con % de los goles del equipo, faltas directas y penales vs liga,
disciplina, cara a cara con cada rival.

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

## API

- `GET /api/catalogo` — clubes con sus categorías y ramas
- `POST /api/informes` `{"clubId":74,"categoria":"INFANTIL","rama":"FEMENINO"}` → `202 {id, estado}`
- `GET /api/informes/{id}` — estado: `PENDIENTE`, `EXTRAYENDO`, `ANALIZANDO`, `GENERANDO_PDF`, `LISTO`, `ERROR`
- `GET /api/informes/{id}/pdf`

## Tests

```bash
./mvnw test
```

Los parsers y el motor se prueban contra HTML real guardado en `src/test/resources/fixtures`
(13/10/2026). Si SIDGAD cambia el markup, actualizar los fixtures y ver qué test falla.

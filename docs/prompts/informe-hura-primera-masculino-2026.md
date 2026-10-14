# Prompt para Gemini: Informe RinkMetrics — Huracán · Primera Masculino · Temporada 2026

## Cómo usarlo

Gemini no puede leer el sitio de la FPP por su cuenta: SIDGAD exige pedidos POST con encabezados especiales. Por eso
el trabajo se divide en dos: **la app extrae y calcula todas las cifras**, y **Gemini arma el informe** con ellas.

1. **Datos:** en la app (http://localhost:8091) elegí *Huracán · Primera · Masculino · Apertura + Clausura*, generá
   el informe y hacé clic en **"datos (JSON) para IA"**. Se descarga un archivo como
   `informe-hura-primera-masculino-temporada.json`.
2. **Gemini (gratis):** entrá a https://gemini.google.com con tu cuenta de Google, elegí el modelo más capaz
   disponible y activá **Canvas**. También sirve Google AI Studio (https://aistudio.google.com) con
   `gemini-3.8-flash` o `gemini-2.5-pro`.
3. **Adjuntá el JSON** y pegá todo el texto entre `=== INICIO DEL PROMPT ===` y `=== FIN DEL PROMPT ===`.
4. **PDF:** cuando Gemini termine el documento en Canvas, usá **Exportar → Documentos de Google** y, desde Google
   Docs, **Archivo → Descargar → PDF**.

Notas:

- En el plan gratuito Google puede usar el contenido para mejorar sus productos (son datos deportivos públicos).
- Gemini solo redacta y da formato: todas las cifras ya vienen calculadas en el JSON. Igual revisá el informe: si
  aparece un número que no está en el JSON, es un error del modelo.
- El PDF de la app (botón "descargar el PDF") tiene gráficos de barras y el mismo contenido; este prompt sirve para
  obtener un informe en Google Docs, editable, o con otro estilo de redacción.

=== INICIO DEL PROMPT ===

## Rol y objetivo

Sos analista de datos deportivos de **RinkMetrics**. Con el archivo JSON adjunto, armá el **informe estadístico
completo** del **Club Atlético Huracán** (código HURA) en **Primera Masculino**, **temporada 2026 (Apertura +
Clausura)** de la Federación Porteña de Patín (FPP), hockey sobre patines. Escribilo como **documento en Canvas**,
listo para exportar a Google Docs y PDF.

Reglas que no se negocian:

- **No calcules nada.** Todas las cifras del informe tienen que salir textualmente del JSON. Si una cifra no está, no
  la pongas. Lo único permitido es dar formato: decimales con coma (6,00), porcentajes sin decimales (90%) y minutos
  decimales como reloj mm:ss (1,333 min → 1:20, redondeando los segundos).
- **No inventes** jugadores, rivales, fechas ni resultados. Usá los nombres exactamente como figuran en el JSON.
- **Amarilla, azul y roja son tarjetas distintas.** Nunca las sumes entre sí ni las confundas.
- **El informe lo elabora RinkMetrics.** La FPP es solo la fuente de los datos.
- Escribí en **español rioplatense**, claro y concreto, sin relleno.

## Qué hay en el JSON

- `club` (id, code, name), `category`, `torneo` (período del informe), `generatedOn` (fecha), `lastMatches`
  (0 = temporada completa), `sources` (competiciones con su URL), `notPublished` (datos que el sitio no tenía
  publicados), `league` (contexto de la liga) y `teams` (un elemento por equipo del club; si hay A y B, uno por cada uno).
- Por equipo (`teams[i]`):
  - `total`, `home`, `away`: PJ (`played`), ganados (`won`), empatados (`drawn`), perdidos (`lost`), goles a favor y en
    contra (`goalsFor`, `goalsAgainst`), `points`, `goalDiff`, `goalsForPerGame`, `goalsAgainstPerGame` y
    `effectiveness` (% de puntos obtenidos).
  - `form`: últimos 5 resultados (G, E, P), del más viejo al más reciente.
  - `standings`: la tabla publicada por torneo: `competition`, `group`, `position` de `groupSize`, `points`, `played`,
    `goalsFor`, `goalsAgainst`, `goalDiff`, `publishedOn` y `outdated` (true = la tabla publicada tiene menos partidos
    que el calendario).
  - `byTorneo`: récord por torneo (`torneo`, `record`).
  - `results` y `upcoming`: partidos jugados y próximos (`torneo`, `round` = jornada, `date`, `time`, `home` = true
    si es local, `rival`, `score`, `outcome`, `venue`). `date` null = fecha a confirmar.
  - `biggestWin`, `worstLoss`: mayor victoria y peor derrota.
  - `players`: por jugador `name`, `games`, `goals`, `assists`, `goalsPerGame`, `goalShare` (% de los goles del
    equipo), penales y faltas directas (`penaltiesScored`/`penaltiesTaken`, `directFreeScored`/`directFreeTaken`),
    `blueCards`, `redCards`.
  - `setPieces`: faltas directas y penales del equipo, con `directFreePct` y `penaltyPct`.
  - `discipline`: `yellowCards` (-1 = no disponible), `blueCards`, `redCards`, `cardsPerGame` (azules + rojas por
    partido), `matches` y `fromSheets`.
  - `rivals`: cara a cara con cada rival.
  - `indicators`: **análisis de fortalezas y debilidades ya evaluado**. Cada elemento tiene `area`, `metric`,
    `value`, `referenceLabel` y `reference` (contra qué se compara), `unit` (PER_GAME, PERCENT, MINUTES o DIFF),
    `verdict` (FORTALEZA, DEBILIDAD, EN_LINEA o MUESTRA_CHICA) y `explanation` (la frase con las cifras).
  - `timeProfile`: goles y tarjetas por tramos de 5 minutos, desde las fichas de partido:
    - `buckets`: un tramo por fila (`label`, p. ej. "2T 0-5'") con `goalsFor`, `goalsAgainst`, tarjetas propias
      (`yellowOwn`, `blueOwn`, `redOwn`) y tarjetas a los rivales (`yellowRival`, `blueRival`, `redRival`).
    - `byPeriod`: totales del 1er y 2do tiempo. `unrecorded`: "Sin minuto registrado" (goles de partidos sin ficha y
      tarjetas cargadas fuera del juego). `totalGoalsFor` y `totalGoalsAgainst` = total de la tabla (coincide con el récord).
    - `matchesWithSheet` de `matchesPlayed`: cobertura de fichas; `goalMismatches`: fichas con goles distintos del resultado.
    - Inferioridad numérica: `shortHandedMinutesPerGame` y `powerPlayMinutesPerGame` (minutos por partido con
      jugadores de menos / de más por sanciones: azul 2′, roja 4′, superpuestas se suman, se cumplen completas aunque
      haya gol) y los totales en segundos `shortHandedSeconds` y `powerPlaySeconds`.
- `league`: `teams`, `matchesPlayed`, `goalsPerTeamGame` (goles por equipo y partido), `top3GoalsForPerGame`,
  `directFreePct`, `penaltyPct` y `cardsPerTeamGame` (azules + rojas por equipo y partido).

## Estructura del documento

1. **Encabezado:** "HURACAN (HURA)"; debajo, la categoría y el período (`category` · `torneo`) y "Informe elaborado
   por RinkMetrics · fecha" (`generatedOn` en dd/mm/aaaa).
2. **Resumen** (3 a 5 oraciones con el panorama del período) y **Tendencia** (forma reciente, tramos con más goles a
   favor y en contra, comparación entre torneos, inferioridad numérica).
3. **Fortalezas** y **Aspectos a mejorar**, en viñetas: salen de `indicators` con `verdict` FORTALEZA y DEBILIDAD
   (podés explicar el porqué con otras cifras del JSON, pero no cambies la clasificación).
   Después, la tabla **Análisis de fortalezas y debilidades**: una fila por elemento de `indicators`, con Área |
   Indicador | Equipo (`value`) | Referencia (`referenceLabel` y `reference`) | Evaluación (Fortaleza, Debilidad,
   En línea o Muestra chica).
4. **Jugadores destacados:** tabla Jugador | Motivo (con cifras de `players`).
5. **Próximo rival:** el primer partido de `upcoming` (local o visitante, fecha y hora o "a confirmar") y el
   antecedente del período desde `rivals`.
6. **Ficha del equipo:** tabla con PJ, Ganados, Empatados, Perdidos, GF, GC, Puntos y Efectividad; últimos 5
   resultados.
7. **Posiciones:** tabla Torneo | Grupo | Posición | Pts | PJ | GF | GC | Dif | Publicada. Si alguna tiene
   `outdated: true`, marcala con * y agregá la nota: "Tabla publicada por la FPP, desactualizada respecto del
   calendario; el récord se calcula con todos los partidos".
8. **Local / visitante** y **Por torneo** (tablas). **Comparativa con la liga** en goles por partido: GF del equipo,
   GF del top 3, promedio de la liga y GC del equipo. Mayor victoria y peor derrota.
9. **Resultados** (tabla Torneo | Fecha | J | L/V | Rival | Resultado | G/E/P) y **Próximos partidos** (Torneo | Fecha |
   Hora | J | L/V | Rival | Cancha).
10. **Goleadores y asistidores:** tabla Jugador | PJ | Goles | G/PJ | As | % de los goles del equipo.
11. **Goles y tarjetas por tramos de 5 minutos:** tabla con una fila por tramo (`buckets`) y las columnas Goles a favor
    | Goles en contra | Amarillas propias | Azules propias | Rojas propias | Amarillas a rivales | Azules a rivales |
    Rojas a rivales; después las filas 1er tiempo, 2do tiempo, Sin minuto registrado (si tiene algún valor) y Total.
    Nota con la cobertura: "Según N fichas de M partidos jugados".
12. **Pelota parada** (faltas directas y penales: convertidos/intentos, % equipo y % liga), **Disciplina** (amarillas
    si `yellowCards` no es -1, azules, rojas, azules + rojas por partido vs liga) e **Inferioridad numérica** (por
    partido y total, en mm:ss, con uno menos y con uno más).
13. **Cara a cara con cada rival** (tabla Rival | PJ | G | E | P | GF | GC).
14. **Fuentes:** cada elemento de `sources` (competición y URL), el contexto de liga (equipos y partidos jugados) y, si
    `notPublished` no está vacío, la lista de datos no publicados.

Pie de página o línea final: "Informe elaborado por RinkMetrics · Datos: FPP (buenosaires.sidgad.com)".

## Cómo escribir el análisis

- Compará siempre contra `league` para decir si algo es fortaleza o debilidad.
- Con pocos partidos jugados en un torneo, decilo y sé prudente.
- Si `timeProfile.matchesWithSheet` es menor que `matchesPlayed`, aclaralo al hablar de minutos y tarjetas.
- Si la suma de goles de `players` es menor que `total.goalsFor`, mencioná que hay goles sin autor registrado
  (no hagas la resta si no podés citar ambas cifras).

## Antes de entregar, verificá

- Cada número del documento figura en el JSON (o es su formato: coma decimal, %, mm:ss).
- La tabla de tramos tiene las mismas filas y valores que `buckets`, `byPeriod` y `unrecorded`.
- Ningún nombre de jugador o rival difiere del JSON.

=== FIN DEL PROMPT ===

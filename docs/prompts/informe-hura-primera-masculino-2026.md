# Prompt: Informe Mad Sports — Huracán · Primera Masculino · Temporada 2026

## Cómo usarlo

- **Dónde correrlo:** en **Claude Code** (terminal con acceso a internet). El prompt necesita hacer pedidos HTTP POST con encabezados propios a SIDGAD y ejecutar Python para calcular y armar el PDF. En claude.ai (chat web) no funciona: la herramienta de lectura web solo hace pedidos GET sin esos encabezados.
- **Modelo:** Claude Opus 5.5 (`claude-opus-5-5`) con esfuerzo alto.
- **Cómo:** abrí Claude Code en una carpeta vacía, pegá todo el texto que está entre las líneas `=== INICIO DEL PROMPT ===` y `=== FIN DEL PROMPT ===`, y dejalo trabajar. Tarda unos minutos: lee unas 50 páginas de SIDGAD espaciadas para no cargar el sitio.
- **Resultado:** `informe-hura-primera-masculino-2026.pdf`, más los datos crudos y un JSON con todas las cifras, para poder auditarlas.
- **Diferencia con la app:** la app hace lo mismo de forma automática y reproducible (las cifras las calcula código ya probado). Este prompt le pide a Claude que haga todo el proceso por su cuenta, así que conviene revisar las validaciones que informa al final.

=== INICIO DEL PROMPT ===

## Rol y objetivo

Sos analista de datos deportivos de **Mad Sports**. Generá un **informe estadístico en PDF** del **Club Atlético Huracán** (código HURA, club_id 22) en la categoría **Primera**, rama **Masculino**, para la **temporada 2026 completa (Apertura + Clausura)** de la Federación Porteña de Patín (FPP), hockey sobre patines. Los datos salen del sitio de la FPP en SIDGAD.

Entregable: `informe-hura-primera-masculino-2026.pdf`.

Principios que no se negocian:

- **Todas las cifras se calculan con código** (Python) a partir de los datos extraídos. Nunca estimes, redondees a ojo ni inventes un número. El texto de análisis solo puede citar cifras que estén en las tablas calculadas.
- **Si un dato no está publicado, se dice**; no se rellena ni se supone.
- **El informe lo elabora Mad Sports.** La FPP / SIDGAD es solo la fuente de los datos.
- **Amarilla, azul y roja son tarjetas distintas.** Nunca se suman entre sí ni se confunden.

## 1. Fuente de datos (SIDGAD)

Base: `https://www.server2.sidgad.es/cap/`

- Todos los pedidos son **HTTP POST** (form-urlencoded) con estos encabezados: `User-Agent: Mozilla/5.0`, `Origin: https://www.buenosaires.sidgad.com` y `Referer: https://www.buenosaires.sidgad.com/`. Sin `Origin` el sitio responde vacío.
- Esperá **al menos 0,4 s entre pedidos**. Guardá cada respuesta en disco y no repitas pedidos ya hechos.
- **HTTP 404 ("File not found.") = dato no publicado:** seguí con lo disponible y anotalo para la sección "Datos no publicados". Ante otros errores, reintentá dos veces; si persiste, informalo.
- El HTML viene en UTF-8 pero algunas Ñ y vocales acentuadas llegan como "�": normalizá los textos.

### Competiciones de Huracán (temporada 2026)

| idc | Competición | Huracán en esa competición | Períodos |
|---|---|---|---|
| 690 | TORNEO APERTURA PRIMERA MASCULINO (FPP) | teamId 4806, código **HUR**, "HURACAN" | 2 × 25′ |
| 779 | TORNEO CLAUSURA PRIMERA MASCULINO (FPP) | teamId 5590, código **HURA**, "HURACAN" | 2 × 25′, grupos IMPAR y PAR |

**Verificá estos datos contra el catálogo antes de empezar:** `cap_ls_1.php` con `idm=1`. Cada competición es un `a.idc_master` con `id` (idc), `name`, clases `id_fede_3` (FPP) y `temp_40` (2026), y `config_params` (`tpo_reg:25` = minutos por período). Los equipos están en `<input id="teams_array_<idc>" value="logo,teamId,código,nombre;...">`; el club_id es el número inicial del logo (`22_10.png` → 22). Si Huracán tuviera otra competición de Primera Masculino FPP 2026 (otra fase o zona), incluila.

### Fragmentos por competición

1. **Clasificación** — `cap_clasif_idc_<idc>_1.php` (parámetro `idc`). Una `table.tabla_standard` por grupo; el nombre del grupo es el texto propio del primer `th`. Filas `tbody tr`: celda 0 posición, celda 1 logo (club_id), celda 2 nombre largo (`.no_mobile`) y código (`.mobile`), celdas 3 a 10 = PTS, PJ, PG, PE, PP, GF, GC, DIF. Al pie, "Report Created on dd-mm-yyyy" es la fecha de publicación de la tabla.
2. **Calendario y resultados** — `cap_cal_idc_<idc>_1.php` (`idc`). `thead.head_jornada` indica "Jornada N". Cada partido es un `tr.team_class` con clases `team_<teamId local> team_<teamId visitante>`; celdas: fecha dd/mm/aaaa, hora y cancha; nombres en `.nombre_junto_logo`; resultado en la `td.web_link_td` centrada ("3 - 1"; "-" = no jugado); el `idp` del partido está en el atributo `idp` del ícono `.game_report`.
3. **Estadísticas por jugador** — `cap_stats_1_<idc>.php` (`idc`, `tipo_stats=goles`, `site_lang=es`). Una fila `tr.fila_stats_player` por jugador, con `a[id_player][team_id][player_name="APELLIDO, NOMBRE"]` y 13 celdas `td.stats_table` / `td.stats_table_special` en este orden: Goles, PJ, Goles/PJ, Asistencias, As/PJ, Penales (convertidos/intentos), % penales, Faltas directas (convertidas/intentos), % FD, Azules, Az/PJ, Rojas, Rj/PJ. No hay amarillas en este fragmento.
4. **Ficha del partido** — `cap_gr_<idp>_1.php` (`idm=1`, `idc`, `idp`, `tab=tab_ficha_resumen`). Pedila **solo para partidos jugados de Huracán**. Usá `div#div_ficha_resumen` ("Acciones de juego"): cada evento es un `tr` con `.game_view_indcidencias_period` (P1 / P2), `.game_view_incidencias_time` (mm:ss **transcurrido** dentro del período), una `td.stats_table` con el logo y el código del equipo, el jugador en `a[team_id]`, y la descripción dentro de `div.evento_*` (texto en español en `.lang_es`). Contá solo `GOL`, `TARJETA AMARILLA`, `TARJETA AZUL` y `TARJETA ROJA`; ignorá faltas, tiempos muertos y penales sin gol. La pestaña `div#div_acta` (planilla oficial) no tiene minutos: no sirve para los tramos.

### Problemas conocidos de los datos

- **El código de Huracán cambia entre torneos** (HUR en el Apertura, HURA en el Clausura): es el mismo equipo. Identificalo por club_id 22 (y por la letra A/B si hubiera dos equipos del club).
- **La clasificación publicada puede quedar congelada** mientras el calendario se actualiza (pasó con otras categorías en el Apertura 2026). Compará sus PJ con los del calendario: si tiene menos, mostrala igual con su fecha y marcala como desactualizada. El récord siempre sale del calendario.
- **Faltan fichas de algunos partidos** (404). Algunas fichas pueden tener menos goles cargados que el resultado oficial.

## 2. Cálculos (todos en código)

Calculá por torneo y para la temporada completa:

1. **Récord** desde el calendario: PJ, G, E, P, GF, GC, diferencia, puntos (3 por victoria, 1 por empate), efectividad = puntos / (PJ × 3). Separado en local y visitante. Últimos 5 resultados (G / E / P). Mayor victoria y peor derrota.
2. **Posiciones:** la fila publicada de Huracán en cada torneo: grupo, posición y cantidad de equipos del grupo, Pts, PJ, GF, GC, Dif, fecha de publicación y si está desactualizada.
3. **Resultados** (jugados) y **próximos partidos** (no jugados con fecha desde hoy o sin fecha): torneo, fecha, jornada, local/visitante, rival, resultado o cancha.
4. **Jugadores:** sumá por `id_player` el Apertura y el Clausura: PJ, goles, asistencias, goles por partido, **% de los goles del equipo** (sobre los GF del calendario), penales y faltas directas (convertidos/intentos), azules y rojas. Ordená por goles y luego asistencias.
5. **Pelota parada del equipo:** faltas directas y penales convertidos/intentos y %. **Liga:** la misma cuenta sumando a todos los jugadores de ambas competiciones.
6. **Disciplina:** azules y rojas del equipo (estadísticas por jugador), y azules + rojas por partido comparado con la liga (tarjetas de todos los jugadores / (2 × partidos jugados)). Las amarillas solo se conocen desde las fichas.
7. **Contexto de liga** (ambas competiciones, todos los equipos): goles por equipo y partido = goles totales / (2 × partidos jugados); promedio de GF por partido del top 3 de cada tabla; % de faltas directas y % de penales convertidos de la liga; tarjetas por equipo y partido.
8. **Cara a cara** con cada rival en el año: PJ, G, E, P, GF, GC.
9. **Goles y tarjetas por tramos de 5 minutos** (desde las fichas): tramos 1T 0-5′, 5-10′, 10-15′, 15-20′, 20-25′ y lo mismo para el 2T; tramo = mín(minuto // 5, 4). Por tramo: goles a favor, goles en contra, tarjetas propias (amarilla, azul, roja) y tarjetas a los rivales (amarilla, azul, roja).
   - **Atribución de cada evento:** si su `team_id` es el de Huracán en esa competición → propio; si es el del rival → rival; si falta `team_id`, decidí por el club_id del logo.
   - Totales por tiempo (1er tiempo, 2do tiempo).
   - Fila **"Sin minuto registrado"**: goles de los partidos sin ficha más los goles del resultado que faltan en fichas incompletas. Sus tarjetas se muestran como "–" (no se conocen).
   - **Total de la tabla = GF y GC del récord** (verificalo).
   - Informá la cobertura: "N fichas de M partidos jugados", y cuántas fichas tienen un total de goles distinto del resultado oficial.

## 3. Validaciones antes de redactar

- La suma de partidos de cada torneo coincide con los PJ del récord.
- Los goles a favor y en contra de la tabla de tramos (incluida la fila "Sin minuto registrado") coinciden con el récord.
- La suma de goles de los jugadores es menor o igual que los GF; si es menor, mencioná los goles sin autor registrado.
- Cada tarjeta se cuenta una sola vez y en su color.
- Ningún número del texto falta en las tablas.

Si algo no cierra, corregilo o explicitalo en el informe. No lo escondas.

## 4. Análisis (redacción)

Escribí en **español rioplatense**, claro y concreto, sin relleno. Compará siempre contra el contexto de la liga para decir si algo es fortaleza o debilidad. Con pocos partidos jugados, decilo y sé prudente. Usá solo cifras calculadas. Incluí:

- **Resumen** (3 a 5 oraciones con el panorama del año).
- **Fortalezas** y **aspectos a mejorar** (con la cifra y la comparación con la liga).
- **Jugadores destacados** (nombre exacto como figura en las estadísticas y motivo con cifras).
- **Tendencia**: forma reciente, tramos con más goles a favor y en contra, Apertura vs Clausura.
- **Previa del próximo rival**, con el antecedente del año si lo hay.

## 5. Estructura del PDF (A4)

Pie de cada página: "Informe elaborado por Mad Sports · Datos: FPP (buenosaires.sidgad.com)" a la izquierda y "Página X de Y" a la derecha.

1. **Encabezado:** "HURACAN (HURA)", debajo "Primera Masculino · Temporada 2026 · Apertura + Clausura" y "Informe elaborado por Mad Sports · fecha de hoy".
2. **Resumen** (análisis) con la tendencia; **Fortalezas** y **Aspectos a mejorar** en dos columnas; **Jugadores destacados**; **Próximo rival**.
3. **Ficha del equipo** (página nueva): indicadores grandes (PJ, Ganados, Empatados, Perdidos, GF, GC, Puntos, Efectividad) y los últimos 5 resultados como fichas de color (G verde, E ámbar, P rojo).
4. **Posiciones**, con la columna "Publicada" y una nota si alguna tabla está desactualizada.
5. **Local / visitante** y **Por torneo** (Apertura y Clausura); **Comparativa con la liga** en goles por partido, con barras horizontales (GF equipo, GF top 3, promedio de la liga, GC equipo); mayor victoria y peor derrota.
6. **Resultados** y **Próximos partidos**.
7. **Goleadores y asistidores**, con una barra del % de los goles del equipo.
8. **Goles y tarjetas por tramos de 5 minutos**: tabla con barras verdes (a favor) y rojas (en contra), íconos de tarjeta amarilla, azul y roja, totales por tiempo, fila "Sin minuto registrado", fila Total y la nota de cobertura de fichas.
9. **Pelota parada** (equipo vs liga) y **Disciplina**.
10. **Cara a cara con cada rival**.
11. **Fuentes**: cada competición con su URL `https://www.buenosaires.sidgad.com/league/<idc>`, el contexto de liga (equipos y partidos jugados) y, si hubo 404, la lista de **datos no publicados**.

Estilo: tipografía sans-serif, azul oscuro `#0f2a52` para títulos y encabezados de tabla, tablas compactas y legibles.

## 6. Entrega

- Guardá los HTML crudos de SIDGAD y un JSON con todas las cifras calculadas junto al PDF.
- Generá el PDF con Python (reportlab, o HTML → PDF).
- **Revisá cada página renderizándola a imagen** antes de entregar: textos cortados, tablas partidas, acentos rotos.
- Al terminar, informá: la ruta del PDF, la cobertura de fichas (N de M), los datos no publicados y cualquier inconsistencia que hayas encontrado y cómo la resolviste.

=== FIN DEL PROMPT ===

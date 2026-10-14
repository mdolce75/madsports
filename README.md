# FPP Stats — Mad Sports

Aplicación de **Mad Sports** que genera un **informe estadístico en PDF** de un club de hockey sobre patines de la
Federación Porteña de Patín (FPP), con análisis redactado por IA (Google Gemini, plan gratuito).

**Filtros:** Año (2026, 2027) · Club · Período (Apertura, Clausura, Apertura + Clausura, Últimos 5 partidos) · Rama
(solo las que tiene el club) · Categoría (Infantil, Prejuvenil, Juvenil, Juniors, Reserva, Primera; solo las del club
en esa rama). Si SIDGAD todavía no tiene un año, la web lo informa y no deja generar el informe.

**Equipos A y B:** son equipos distintos y cada uno tiene su informe. Si el club tiene dos equipos en una categoría y
rama, la categoría se ofrece como "Infantil A" / "Infantil B" (en la API: `"categoria": "INFANTIL:B"`). La
letra sale del código o del nombre en SIDGAD ("CAVS B", "CAVSB", "VELEZ B"); si el club tiene un solo equipo en
todo el año va sin letra aunque SIDGAD lo llame "A", y un equipo con el logo del club pero sin su código ni su nombre
no se cuenta como del club (ver `TeamKeys`). 

En "Últimos 5 partidos", récord, resultados, rivales y el análisis por minuto usan solo esos partidos;
goleadores, pelota parada y disciplina son del torneo completo (SIDGAD no publica estadísticas por partido).

## Cómo funciona

```
Web (index.html) → POST /api/informes → job en segundo plano
  1. Extracción   SidgadClient: POST a server2.sidgad.es, en paralelo (hasta 6 a la vez, máx. 10/s) + cache
  2. Cálculo      StatsEngine: todas las cifras, en código
  3. Análisis     GeminiAnalyst: un pedido a Gemini con todas las cifras → JSON con esquema fijo
                  (sin API key o ante cualquier falla: RuleBasedAnalyst)
  4. PDF          Thymeleaf (templates/report.html) → openhtmltopdf
```

La IA **no calcula números**: recibe las cifras ya calculadas en un solo pedido (overview, contexto de liga y
detalle por equipo) y devuelve el texto en un esquema JSON fijo, que se valida. Los jugadores destacados que no
existen en los datos se descartan. Un solo pedido por informe respeta el límite del plan gratuito; ante un 429
espera y reintenta una vez.

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

FPP = `id_fede_3`. El año se traduce a la temporada de SIDGAD (`temp_N`) con el selector de temporadas de la página
principal del portal (`a.select_temporada[id_temp][temp_name]`, p. ej. 40 = 2026). Años ofrecidos:
`fpp.sidgad.years` en `application.yml`. La tabla de posiciones de SIDGAD puede
quedar congelada (ej.: Apertura 2026 publicada el 03/05): el informe la muestra con su fecha y la marca
como desactualizada; el récord se calcula siempre desde el calendario.

### Estadísticas del informe

Ficha (PJ/G/E/P/GF/GC/puntos/efectividad), últimos 5, posiciones por torneo, local/visitante,
Apertura vs Clausura, comparativa de goles por partido contra la liga y el top 3, resultados y próximos
partidos, goleadores/asistidores con % de los goles del equipo, faltas directas y penales vs liga,
disciplina, cara a cara con cada rival, y **goles a favor/en contra y tarjetas propias/de los rivales
(amarilla, azul y roja por separado) por tramos de 5 minutos**, según las actas.

**Faltas:** faltas de equipo (eventos "FALTA" de las fichas; "FALTA DIRECTA PARA" no cuenta) por partido del
equipo, de sus rivales en esos mismos partidos y promedio de la liga (todas las fichas de las competiciones del
informe: la primera vez se leen unas 120 fichas, ~20 s en paralelo; después quedan en cache 24 h). Por tramo de 5′: faltas
propias y rivales, y cómo iba el equipo al empezar el tramo (diferencia de gol promedio y % de partidos ganando,
empatando o perdiendo, según el minuto de los goles).

**Análisis de faltas:** texto escrito (Gemini, o por reglas si no hay clave o si la IA no lo entrega) sobre el
volumen contra la liga y los rivales, el reparto por tiempo y tramo, el ritmo de faltas según el marcador (faltas
cada 10 minutos ganando, empatando y perdiendo, con el tiempo jugado en cada situación) y la relación con las azules.

**Inferioridad numérica:** minutos por partido con uno menos y con uno más, desde las fichas: azul = 2′ y roja = 4′
desde la sanción (las sanciones superpuestas se suman: dos azules a la vez = 4′; una sanción del final del 1T sigue
en el 2T; se corta al final del partido; la sanción se cumple completa aunque el rival haga un gol). Período 10 de SIDGAD = descanso (la sanción arranca en el 2T); período 11 u otros =
fuera del juego: la tarjeta se cuenta sin minuto ni inferioridad.

**Goles con uno menos** (informe de club y de jugador): por cada partido con sanciones (del equipo; en el informe de
jugador, las suyas) se informa:
- cuántos goles recibió y cuántos hizo mientras duraba la sanción;
- cómo terminó el partido;
- en total: partidos, goles recibidos cada 10' con uno menos contra el resto del tiempo, y ganados, empatados y
  perdidos.

Un gol cuenta si cae dentro de una sanción. Cuando hay sanciones superpuestas, para contar los goles se toma el
tiempo en que hubo al menos uno menos.

**Gráficos:** el informe incluye 6 gráficos de barras y de dona, generados en el servidor con Java2D
(`report/Charts.java`) como PNG embebidos en el PDF. Son: dona de resultados (G/E/P), barras de diferencia de gol
partido a partido, dona de reparto del gol (top 5 y "Otros"), columnas de goles a favor y en contra por tramo de 5′,
columnas de faltas propias y de los rivales por tramo, y dona del tiempo jugado ganando, empatando o perdiendo.
Cada gráfico lleva leyenda y valores visibles.

**Escudo del club:** la cabecera del informe y el título de cada equipo llevan el escudo del club. Sale de SIDGAD
(`sidgad.cloud/cap/images/logos_clubes/`, el archivo que figura en `teams_array`) y se encaja en una placa blanca
cuadrada. Queda en cache 24 h; si no se puede bajar, el informe sale sin escudo.

La duración de los períodos sale de `tpo_reg` en el catálogo (20' de Infantil a Juvenil, 25' de Juniors a
Primera). SIDGAD no publica el acta de todos los partidos: el informe indica cuántas actas se usaron sobre el
total de partidos jugados.

### Datos no publicados (404)

Si un fragmento de SIDGAD responde **404** (tabla, calendario, estadísticas o acta no publicados), el informe
**se genera igual** con lo disponible, sin error en la web, y el PDF lista al final qué datos faltaban. Un 404 no
se cachea (se reintenta en el próximo informe). Cualquier otro error (5xx, timeout) sí corta el informe.

## Usuarios y perfiles

La web pide registro e ingreso (Spring Security, sesión con cookie, CSRF con `XSRF-TOKEN`). Cada usuario tiene uno o
más perfiles, pensados para venderse por separado: `CLUBFPP`, `CLUBFSP`, `CLUBCNT`, `PLAYERFPP`, `PLAYERFSP`,
`PLAYERCNT`. Por ahora el registro ofrece solo **Club FPP** o **Jugador FPP**, y uno excluye al otro.

- **Registro** (`/registro.html`): usuario, clave (mínimo 8, se guarda con BCrypt), email y club, obligatorios.
  No pide nombre ni apellido: el club no los carga y el jugador los toma de la FPP (tal como los separa el acta).
  Para Jugador FPP es obligatorio el **jugador**, que se elige de la planilla de un equipo del club
  (club → categoría → nombre). La cuenta queda vinculada al `id_player` de la FPP, y cada jugador tiene un solo
  usuario.
- **Confirmar el email:** al registrarse llega un mail con un enlace (vale 48 h). Hasta confirmarlo no se puede
  ingresar: el login avisa y ofrece reenviar el mail.
- **Recuperar la clave:** "¿Olvidaste tu clave?" en el ingreso manda un enlace de un solo uso que vale 1 h
  (`/restablecer.html`). Para no revelar qué emails están registrados, pedirlo siempre responde lo mismo. Se manda
  un mail por minuto como mucho, y en la base queda solo el hash del token.
- **Home** (`/`): pública, con los dos informes de ejemplo y los accesos a registrarse e ingresar.
- **Club FPP** (`/club.html`): la pantalla de informes de siempre, con el club fijo en el del usuario. La API rechaza pedidos
  de otro club (403), y cada informe solo lo ve y lo baja quien lo pidió.
- **Jugador FPP** (`/jugador.html`): muestra club, nombre y apellido, y jugador en la FPP (no editables). Pide año y
  período (Apertura, Clausura o Apertura + Clausura) y genera un PDF que trae, por cada categoría del club en que
  jugó y en total: partidos jugados, ganados, empatados y perdidos; goles; tarjetas amarillas, azules y rojas por
  separado, cada una con su promedio por partido; y penales y libres directos (convertidos, tirados y efectividad,
  con donas). Además, goles y tarjetas por tramos de 5 minutos (gráfico de columnas, con las tarjetas apiladas por
  color) y el detalle partido a partido.
- **Categorías en que puede jugar:** el jugador puede subir de categoría, nunca bajar. Infantil juega en Infantil,
  Prejuvenil y Juvenil; Prejuvenil, en Prejuvenil, Juvenil y Juniors; Juvenil, en Juvenil, Juniors, Reserva y
  Primera; Juniors, en Juniors, Reserva y Primera; Reserva y Primera, en Reserva y Primera. El informe lee solo las
  actas de esas categorías, en la rama del jugador. La categoría y la rama son las del equipo que eligió al
  registrarse.

El informe del jugador sale de las **actas**: cada acta trae la planilla de jugadores de cada equipo (primero el local),
con `id_player`, goles, tarjetas, penales y libres directos. Un partido cuenta si el jugador figura en la planilla
de su equipo. El minuto de goles y tarjetas sale de los eventos del acta. Si la planilla anota algo que no tiene
evento (pasa con algunas actas), va a "Sin minuto registrado". La tabla de
estadísticas de la FPP no alcanza, porque solo lista a los que hicieron goles. Se leen las actas de todos los equipos
del club en el período y en esas categorías (la primera vez tarda unos 10 s; después quedan en cache 24 h). Los
partidos sin acta publicada se informan aparte.

Los usuarios se guardan en H2 en archivo (`./data`, fuera de git); el esquema lo crea Flyway
(`db/migration`). En producción, `FPP_DB_URL` apunta a otra base.

## Correr

Requiere JDK 21. Los secretos locales (SMTP de Resend, Gemini) van en un `.env` junto al proyecto, fuera de git;
ver `.env.example`. En producción se cargan como variables de entorno.

Los mails (confirmar el email, elegir una clave nueva) salen en HTML con la identidad de la web (`templates/mail/`)
y el logo embebido, más una versión en texto. Con Resend: host `smtp.resend.com`, usuario `resend` y la API key
como clave (alcanza con permiso de solo envío).

```bash
export GEMINI_API_KEY=...   # opcional: sin key se usa el análisis por reglas
./mvnw spring-boot:run
```

Abrir http://localhost:8080.

| Variable | Default | |
|---|---|---|
| `GEMINI_API_KEY` | — | Habilita el análisis con Gemini |
| `FPP_AI_MODEL` | `gemini-3.8-flash` | Modelo de Gemini (con plan gratuito) |
| `FPP_AI_ENABLED` | `true` | `false` fuerza el análisis por reglas |
| `FPP_DB_URL` | `jdbc:h2:file:./data/fpp-stats` | Base de usuarios |
| `FPP_DB_USER` / `FPP_DB_PASSWORD` | `sa` / vacío | Credenciales de la base |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | vacío, `587` | SMTP para los mails (Resend, Brevo, Gmail...). Sin `SMTP_HOST`, los mails se escriben en el log con su enlace |
| `MAIL_FROM` | `Mad Sports <info@madsportsapp.com>` | Remitente (tiene que ser un dominio verificado en el proveedor) |
| `APP_BASE_URL` | `http://localhost:8080` | Dirección pública de la web, para los enlaces de los mails |

**API key gratuita:** se crea en Google AI Studio (https://aistudio.google.com/apikey) con una cuenta de Google.
En el plan gratuito Google puede usar el contenido enviado para mejorar sus productos (son datos deportivos
públicos de la FPP) y hay límites de pedidos por minuto y por día; los modelos y límites cambian seguido.

Categorías relevadas: `fpp.sidgad.levels` en `application.yml`.

Cada análisis con Gemini deja en el log el consumo de tokens (`Consumo de Gemini para ...`).

## API

Públicos: registro, sesión, catálogo e informes de ejemplo. El resto pide sesión con el perfil que corresponde
(sin sesión, `401`; con otro perfil, `403`). Los POST llevan el header `X-XSRF-TOKEN` con el valor de la cookie.

- `POST /api/registro` `{"perfil":"PLAYERFPP","usuario":"...","clave":"...","email":"...","clubId":22,"categoria":"PRIMERA","rama":"MASCULINO","jugadorId":5973}` → `201`
- `GET /api/registro/jugadores?clubId=22&categoria=PRIMERA&rama=MASCULINO`: plantel del equipo según sus actas
- `POST /api/login` (form: `usuario`, `clave`) → `{inicio}`; `POST /api/logout`
- `GET /api/sesion`: usuario, perfiles, club y jugador (`autenticado: false` sin sesión)
- `GET /api/verificar?token=...`: enlace del mail; confirma el email y lleva al ingreso.
  `POST /api/verificacion/reenviar` `{"email"}` o `{"usuario"}`
- `POST /api/clave/olvido` `{"email"}`; `POST /api/clave/restablecer` `{"token","clave"}`
- `POST /api/jugador/informes` `{"anio":2026,"periodo":"TEMPORADA"}` → `202 {id, estado}`; `GET /api/jugador/informes/{id}` y `/pdf` (PLAYERFPP)
- `GET /api/catalogo?anio=2026` — clubes con sus categorías y ramas del año (sin `anio`: el más reciente con datos);
  `available: false` y `message` si el año no tiene datos
- `GET /api/ejemplo.pdf` — informe modelo con datos ficticios ("Club Ejemplo"), marcado en cada página; no consulta
  SIDGAD ni la API de Gemini. Enlazado desde la web.
- `GET /api/ejemplo-jugador.pdf`: informe modelo de jugador con datos ficticios ("Tomás Ejemplo", de Juniors, que
  también juega en Primera del "Club Ejemplo"). Se arma con el mismo motor que los informes reales. Enlazado desde
  el login, el registro y la pantalla de jugador.
- `POST /api/informes` (CLUBFPP, solo su club) `{"clubId":74,"categoria":"INFANTIL","rama":"FEMENINO","periodo":"TEMPORADA","anio":2026}` → `202 {id, estado}`
  (`periodo`: `APERTURA`, `CLAUSURA`, `TEMPORADA` o `ULTIMOS_5`)
- `GET /api/informes/{id}` — estado: `PENDIENTE`, `EXTRAYENDO`, `ANALIZANDO`, `GENERANDO_PDF`, `LISTO`, `ERROR`
- `GET /api/informes/{id}/pdf`

## Tests

```bash
./mvnw test
```

Los parsers y el motor se prueban contra HTML real guardado en `src/test/resources/fixtures`
(13/10/2026). Si SIDGAD cambia el markup, actualizar los fixtures y ver qué test falla.

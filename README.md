<div align="center">

<img src="docs/img/logo.svg" width="96" alt="RinkMetrics">

# RinkMetrics

**Estadísticas de hockey sobre patines, convertidas en informes PDF.**

Informes de equipo, de club y de jugador a partir de los datos de la Federación Porteña de Patín (FPP) y de la
Asociación Mendocina de Patín (AMP), con gráficos, análisis minuto a minuto y texto redactado por IA. Los productos de
Mad Sports: **RinkReports** (informes en pantalla, PDF y Excel) y **RinkMetrics** (métricas en vivo para el DT).

<img src="https://img.shields.io/badge/Java%2021-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21">
<img src="https://img.shields.io/badge/Spring%20Boot%203.5-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 3.5">
<img src="https://img.shields.io/badge/Spring%20Security-6DB33F?style=for-the-badge&logo=springsecurity&logoColor=white" alt="Spring Security">
<img src="https://img.shields.io/badge/Thymeleaf-005F0F?style=for-the-badge&logo=thymeleaf&logoColor=white" alt="Thymeleaf">
<img src="https://img.shields.io/badge/Hibernate-59666C?style=for-the-badge&logo=hibernate&logoColor=white" alt="Hibernate">
<img src="https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white" alt="Flyway">
<br>
<img src="https://img.shields.io/badge/Google%20Gemini-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white" alt="Google Gemini">
<img src="https://img.shields.io/badge/Resend-000000?style=for-the-badge&logo=resend&logoColor=white" alt="Resend">
<img src="https://img.shields.io/badge/Maven-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white" alt="Maven">
<img src="https://img.shields.io/badge/JUnit%205-25A162?style=for-the-badge&logo=junit5&logoColor=white" alt="JUnit 5">
<img src="https://img.shields.io/badge/HTML%20%C2%B7%20CSS%20%C2%B7%20JS-1572B6?style=for-the-badge&logo=html5&logoColor=white" alt="HTML · CSS · JS">

</div>

---

## Índice

- [Qué hace](#qué-hace)
- [Capturas](#capturas)
- [Stack tecnológico](#stack-tecnológico)
- [Cómo funciona](#cómo-funciona)
- [Usuarios y perfiles](#usuarios-y-perfiles)
- [Correr](#correr)
- [API](#api)
- [Arquitectura](#arquitectura)
- [Tests](#tests)
- [Producción](docs/produccion.md)

## Qué hace

| Función | Detalle |
|---|---|
| 🏒 **Informe de equipo** | Resultados, posiciones, goleadores (sin arqueros), pelota parada, disciplina, faltas, fortalezas y debilidades, con análisis escrito por IA (Google Gemini) o por reglas. |
| 👤 **Informe de jugador** | Agrupado por categoría y por torneo (Apertura, Clausura...), con los totales: partidos (G/E/P), goles, tarjetas amarillas, azules y rojas, penales y libres directos, con promedios por partido, el gráfico de progresión de goles en cada torneo y fortalezas y debilidades contra sus compañeros. Cuenta también los partidos sin acta publicada. |
| 📋 **RinkMetrics** | Perfil DT para cargar en vivo, desde el celular, las acciones de cada jugador durante el partido, aunque se corte la señal. |
| ⏱️ **Minuto a minuto** | Goles, tarjetas y faltas por tramos de 5′, cómo iba el partido en cada tramo y goles recibidos con uno menos. |
| 📊 **Gráficos** | Barras y donas en cada PDF, con el escudo del club en la cabecera. |
| 🔐 **Usuarios** | Registro con perfil Club, Jugador o DT de la FPP o de la AMP, confirmación del email, recuperación de clave y un plan mensual con precio en pesos. |
| 🌐 **Fuente** | Datos públicos de la FPP (Buenos Aires) y de la AMP (Mendoza) en SIDGAD, leídos en paralelo y con cache. |

## Capturas

<table>
  <tr>
    <td align="center"><b>Informe de equipo</b></td>
    <td align="center"><b>Informe de jugador</b></td>
  </tr>
  <tr>
    <td><img src="docs/img/informe-club-2.png" alt="Informe de equipo: resultados, gráficos y fortalezas"></td>
    <td><img src="docs/img/informe-jugador-1.png" alt="Informe de jugador: totales y resumen por categoría"></td>
  </tr>
  <tr>
    <td><img src="docs/img/informe-club-1.png" alt="Informe de equipo: portada con análisis"></td>
    <td><img src="docs/img/informe-jugador-2.png" alt="Informe de jugador: bloque de una categoría"></td>
  </tr>
</table>

<sub>Informes de ejemplo con datos ficticios ("Club Ejemplo", "Tomás Ejemplo"): en pantalla en
`/rinkreports/sample.html`, y en PDF y Excel en `GET /api/sample.pdf|.xlsx` y `GET /api/sample-player.pdf|.xlsx`.</sub>

### RinkReports: en pantalla, PDF y Excel

Los informes de jugador y de club viven en **RinkReports** (`/rinkreports`), con el mismo look and feel que RinkMetrics
(tipografías, tableros, planillas y botones de `app.css`) y su propia paleta (`reports.css`): fondo verde noche,
esmeralda como color principal y ámbar para lo que se destaca. La web de RinkMetrics ya no enlaza a los informes: su
menú es solo de RinkMetrics. La portada ofrece las dos opciones,
**Jugador** y **Club**, con su ejemplo y su registro.

Cada informe **se muestra en pantalla** (el mismo contenido que el PDF, dentro de la página) y se **exporta a PDF o a
Excel**. El Excel trae las mismas cifras en tablas para filtrar y trabajar:

- **Club:** una hoja **Resumen** (una fila por categoría: partidos, efectividad, goles, faltas y tarjetas) y una hoja
  por categoría con sus apartados, los top 3, el plantel completo, los indicadores y el análisis.
- **Jugador:** **Resumen** (cada categoría y el total, con la incidencia de goles y tarjetas), **Partidos** (uno por
  fila), **Minutos** (goles y tarjetas por tramo de 5') y **Fortalezas**.

Las direcciones anteriores (`/informes.html`, `/club.html`, `/jugador.html`, `/dt.html`, `/login.html`, `/registro.html`...)
llevan a las nuevas, con los mismos parámetros (`WebConfig.OLD_PAGES`).

### Informe del club completo

La pantalla de informes del club (`/rinkreports/club.html`) arma **un solo informe con todo el club**: primero por **rama** (Masculino
y después Femenino) y, dentro de cada rama, una sección por **categoría** (de Infantil a Primera, solo las que juegan en
el período). Cada categoría lleva:

- **Partidos:** jugados, ganados, empatados, perdidos y efectividad.
- **Goles:** a favor, en contra y promedio por partido.
- **Faltas:** cometidas, recibidas y promedio por partido (de los partidos con acta).
- **Tarjetas:** amarillas, azules y rojas, con su promedio por partido.
- **Top 3 · Promedio de gol:** los tres jugadores con mejor promedio, con goles y partidos.
- **Top 3 · Libres directos** y **Top 3 · Penales:** por efectividad, con convertidos de tirados.
- **Top 3 · Disciplina:** suma todas las tarjetas y muestra cuántas de cada color.
- **Fortalezas y debilidades:** los indicadores de la categoría y un texto de IA (Gemini, o el análisis por reglas si
  no está disponible) por categoría.

Al principio, una tabla de contenido con el resumen de cada categoría. Tarda unos minutos: lee las actas de todas las
categorías; el estado muestra por cuál va ("Categoría 3 de 8 · Juvenil Masculino").

### Informe de equipo: filtros

**Filtros:** Año (2026, 2027) · Club · Período (Apertura, Clausura, Apertura + Clausura, Últimos 5 partidos) · Rama
(solo las que tiene el club) · Categoría (Infantil, Prejuvenil, Juvenil, Juniors, Reserva, Primera; solo las del club
en esa rama). Por la API, sin `categoria` ni `rama` se pide el informe del club completo. Si SIDGAD todavía no tiene un
año, la web lo informa y no deja generar el informe.

**Equipos A y B:** son equipos distintos y cada uno tiene su informe. Si el club tiene dos equipos en una categoría y
rama, la categoría se ofrece como "Infantil A" / "Infantil B" (en la API: `"categoria": "INFANTIL:B"`). La
letra sale del código o del nombre en SIDGAD ("CAVS B", "CAVSB", "VELEZ B"); si el club tiene un solo equipo en
todo el año va sin letra aunque SIDGAD lo llame "A", y un equipo con el logo del club pero sin su código ni su nombre
no se cuenta como del club (ver `TeamKeys`). 

En "Últimos 5 partidos", récord, resultados, rivales y el análisis por minuto usan solo esos partidos;
goleadores, pelota parada y disciplina son del torneo completo (SIDGAD no publica estadísticas por partido).

## Stack tecnológico

| | Capa | Tecnología | Para qué |
|---|---|---|---|
| <img src="https://img.shields.io/badge/Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java"> | Lenguaje y build | **Java 21**, **Maven 3.9.11** (wrapper `./mvnw`) | Compilación, tests y empaquetado en un jar ejecutable |
| <img src="https://img.shields.io/badge/Spring%20Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot"> | Framework | **Spring Boot 3.5.7** (Spring Framework 6.2, Tomcat 10.1 embebido) | Aplicación web, configuración e inyección de dependencias |
| <img src="https://img.shields.io/badge/Spring%20MVC-6DB33F?style=flat-square&logo=spring&logoColor=white" alt="Spring MVC"> | API web | Spring Web MVC, Bean Validation, Jackson 2.19 | API REST en JSON (`/api/...`) |
| <img src="https://img.shields.io/badge/Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white" alt="Security"> | Seguridad | **Spring Security 6.5** | Registro y login con sesión, perfiles por pantalla, CSRF con cookie `XSRF-TOKEN`, claves con BCrypt |
| <img src="https://img.shields.io/badge/Hibernate-59666C?style=flat-square&logo=hibernate&logoColor=white" alt="Hibernate"> | Persistencia | **Spring Data JPA** con Hibernate 6.6, **Flyway 11.7** | Usuarios, perfiles, suscripciones y enlaces de los mails; esquema versionado en `db/migration/{h2,postgresql}` |
| <img src="https://img.shields.io/badge/H2-0000BB?style=flat-square" alt="H2"> | Base de datos | **H2 2.3** en archivo (`./data`) | Desarrollo. En producción se configura otra base con `MADSPORTS_DB_URL` (por ejemplo PostgreSQL) |
| <img src="https://img.shields.io/badge/jsoup-263238?style=flat-square" alt="jsoup"> | Extracción de datos | **jsoup 1.23** | Pedidos a server2.sidgad.es (tablas, calendarios, estadísticas y actas) y lectura del HTML |
| <img src="https://img.shields.io/badge/Caffeine-6F4E37?style=flat-square" alt="Caffeine"> | Cache | **Caffeine 3.2** | Datos de SIDGAD (15 min, catálogo 6 h, actas 24 h), escudos, planteles e informes en curso |
| <img src="https://img.shields.io/badge/Virtual%20threads-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Virtual threads"> | Concurrencia | Hilos virtuales de Java 21 + semáforo | Pedidos a SIDGAD en paralelo (hasta 6 a la vez), sin sobrecargar el sitio |
| <img src="https://img.shields.io/badge/Gemini-8E75B2?style=flat-square&logo=googlegemini&logoColor=white" alt="Gemini"> | Análisis con IA | **Google Gemini** (API `generateContent`, `gemini-3.8-flash`) con `java.net.http` | Texto del informe de equipo. Sin clave, un analista por reglas hace el mismo trabajo |
| <img src="https://img.shields.io/badge/Thymeleaf-005F0F?style=flat-square&logo=thymeleaf&logoColor=white" alt="Thymeleaf"> | PDF | **Thymeleaf 3.1** + **openhtmltopdf 1.1.97** (Apache PDFBox 3.0) | Plantillas HTML que se convierten en PDF |
| <img src="https://img.shields.io/badge/Java2D-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java2D"> | Gráficos | **Java2D** (AWT) | Barras y donas en PNG, embebidos en el PDF; también el logo de los mails y la placa de los escudos |
| <img src="https://img.shields.io/badge/Resend-000000?style=flat-square&logo=resend&logoColor=white" alt="Resend"> | Mails | **Spring Mail** (Jakarta Mail 2.0) por SMTP, con **Resend** | Confirmación de email y recuperación de clave, en HTML con la identidad de la web |
| <img src="https://img.shields.io/badge/HTML5-E34F26?style=flat-square&logo=html5&logoColor=white" alt="HTML5"> | Web | HTML, CSS y JavaScript sin frameworks, fuentes Oswald e Inter (Google Fonts) | Home pública, registro, login, pantallas de club y de jugador |
| <img src="https://img.shields.io/badge/JUnit%205-25A162?style=flat-square&logo=junit5&logoColor=white" alt="JUnit 5"> | Tests | **JUnit 5.12**, AssertJ 3.27, Mockito 5.17, Spring Boot Test, MockMvc, Spring Security Test | Unitarios contra HTML real de SIDGAD (`src/test/resources/fixtures`) e integración de seguridad con H2 en memoria |
| <img src="https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white" alt="GitHub"> | Repositorio | Git + GitHub (`gh` CLI) | Ramas y pull requests |

## Cómo funciona

```
Web (index.html) → POST /api/reports → job en segundo plano
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
| `cap_gr_<idp>_1.php` | Acta del partido: goles y tarjetas con período y minuto, y la planilla de cada equipo (con el arquero marcado "P") |
| `profiles/cap_profileseason_<id_player>_1_<temp>.php` | Ficha de temporada de un jugador: todos sus partidos de cada competición, también los sin acta, con goles, libres, penales, azules y rojas |

Federaciones (`Federation`): **FPP** = `id_fede_3` (Federación Porteña de Patín) y **AMP** = `id_fede_4` (Asociación
Mendocina de Patín). Los fragmentos son los mismos para las dos; los nombres de las competiciones de la AMP tienen otro
formato (`AMP'26- CAMP MASC "APERTURA 26"-CATEGORIA SENIOR`, que se lee del texto visible porque las comillas cortan el
atributo `name`). El año se traduce a la temporada de SIDGAD (`temp_N`) con el selector de temporadas de la página
principal del portal (`a.select_temporada[id_temp][temp_name]`, p. ej. 40 = 2026), que es la misma para las dos
federaciones. Años ofrecidos: `madsports.sidgad.years` en `application.yml`. La tabla de posiciones de SIDGAD puede
quedar congelada (ej.: Apertura 2026 publicada el 03/05): el informe la muestra con su fecha y la marca
como desactualizada; el récord se calcula siempre desde el calendario.

### Estadísticas del informe

Ficha (PJ/G/E/P/GF/GC/puntos/efectividad), últimos 5, posiciones por torneo, local/visitante,
Apertura vs Clausura, comparativa de goles por partido contra la liga y el top 3, resultados y próximos
partidos, goleadores con % de los goles del equipo, faltas directas y penales vs liga,
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

En el informe de jugador, la sección enumera solo los goles que le hicieron a su equipo mientras cumplía su
sanción. Cada gol trae la fecha, el rival, su tarjeta y su minuto, el minuto del gol y el resultado final. Un gol
cuenta si cae dentro de una sanción. Cuando hay sanciones superpuestas, para contar los goles se toma el
tiempo en que hubo al menos uno menos.

**Gráficos:** el informe incluye 6 gráficos de barras y de dona, generados en el servidor con Java2D
(`report/Charts.java`) como PNG embebidos en el PDF. Son: dona de resultados (G/E/P), barras de diferencia de gol
partido a partido, dona de reparto del gol (top 5 y "Otros"), columnas de goles a favor y en contra por tramo de 5′,
columnas de faltas propias y de los rivales por tramo, y dona del tiempo jugado ganando, empatando o perdiendo.
Cada gráfico lleva leyenda y valores visibles.

**Escudo del club:** la cabecera del informe y el título de cada equipo llevan el escudo del club. Sale de SIDGAD
(`sidgad.cloud/cap/images/logos_clubes/`, el archivo que figura en `teams_array`) y se encaja en una placa blanca
cuadrada. Queda en cache 24 h; si no se puede bajar, el informe sale sin escudo.

**Goleadores:** salen de la ficha de temporada de cada jugador del club, que incluye los partidos sin acta; la tabla
de estadísticas de SIDGAD solo suma los partidos con acta (y a veces no se publica), así que sus goles no llegaban a
los del equipo. Quien no tiene ficha conserva su fila de la tabla. Los **arqueros** (marcados "P" en 2 planillas o
más, o en la mayoría de las suyas) no entran en los goleadores ni en el % de los goles; sus tarjetas y su pelota
parada sí suman en las del equipo. No hay **asistencias**: los clubes no las cargan en SIDGAD.

La duración de los períodos sale de `tpo_reg` en el catálogo (en la FPP, 20' de Infantil a Juvenil y 25' de Juniors a
Primera). SIDGAD no publica el acta de todos los partidos (en 2026, cerca del 40% en la FPP): el informe indica
cuántas actas se usaron sobre el total de partidos jugados para lo que necesita minuto (tramos de 5', faltas,
inferioridad).

### Datos no publicados (404)

Si un fragmento de SIDGAD responde **404** (tabla, calendario, estadísticas o acta no publicados), el informe
**se genera igual** con lo disponible, sin error en la web, y el PDF lista al final qué datos faltaban. Un 404 no
se cachea (se reintenta en el próximo informe). Cualquier otro error (5xx, timeout) sí corta el informe.

## Usuarios y perfiles

La web pide registro e ingreso (Spring Security, sesión con cookie, CSRF con `XSRF-TOKEN`). Cada usuario tiene uno o
más perfiles, pensados para venderse por separado: `CLUBFPP`, `CLUBFSP`, `CLUBCNT`, `CLUBAMP`, `PLAYERFPP`,
`PLAYERFSP`, `PLAYERCNT`, `PLAYERAMP`, `DTFPP`, `DTAMP`. El registro ofrece **Club**, **Jugador** o **DT** de la
**FPP** (Buenos Aires) o de la **AMP** (Mendoza): primero se elige la federación y después el perfil, y uno excluye a
los demás. La federación del
perfil decide de dónde salen los clubes, las categorías, los jugadores y los informes (que dicen FPP o AMP).

- **Dos productos:** la home (`/`) es la de **Mad Sports**, la compañía, y presenta sus dos productos: **RinkReports**
  (`/rinkreports`: perfiles Club y Jugador, informes de ejemplo) y **RinkMetrics** (`/rinkmetrics`: las estadísticas en
  vivo del DT, y su plan). Cada producto tiene arriba su propio menú (RinkMetrics: Inicio · Demo · Por qué
  RinkMetrics; RinkReports: Inicio · Ejemplos), salvo la pantalla de RinkMetrics (y su demo): ahí la barra superior lleva
  solo el menú del DT (Control panel · Partidos · Equipo · Estadísticas · Informes) y el logo vuelve a la portada. Cada
  producto tiene su ingreso, que lleva a cada usuario a la pantalla de su perfil.
- **Por qué RinkMetrics** (`/rinkmetrics/why.html`, en el menú): las ventajas de la plataforma, en su propia página.
- **Demo de RinkMetrics** (`/coach-demo.html`, en el menú "Demo"): RinkMetrics completo con datos de ejemplo, sin
  cuenta: arranca en el Control panel y se recorren todas las pantallas (Partidos, con un partido en juego para cargar
  acciones; Equipo, con sus ABM; Estadísticas e Informes). Es la misma página que `/coach.html` (sin copia): en la demo,
  `js/coach/demo-api.js` contesta en el navegador las llamadas a la API, igual que el servidor (nada se guarda). La página de
  la sección muestra capturas de la demo ("Así se ve RinkMetrics", en `img/demo/`); se regeneran con la app levantada:
  `node docs/capturas-demo.mjs` (usa el Chrome instalado).
- **Alta de cuentas de jugador y de DT; la de club, próximamente:** el registro de Informes
  (`/rinkreports/signup.html`) crea cuentas de jugador de la FPP (las de la AMP, próximamente:
  `SIGNUP_PLAYER_AMP`) y el de RinkMetrics
  (`/rinkmetrics/signup.html`), de DT. El alta de club está abierta (`SIGNUP_CLUB`,
  `madsports.signup.club`, en `true` por defecto; en `false`, el registro la muestra como "Próximamente").
- **Planes de RinkReports:** el **Jugador free** (sin costo hasta el 31/12/2026, 5 informes por mes) y el **Club
  free** (sin costo hasta el 31/12/2026, sin límite de informes). Los mensuales (pagos) figuran con el cintillo
  "Próximamente", apagados y borroneados, y con **"Precio a definir"**, mientras `SIGNUP_PLAYER_PAID` y
  `SIGNUP_CLUB_PAID` (`madsports.signup.player-paid` y `club-paid`) sean `false`. Los planes se ven y se eligen
  solo en el registro de cada producto (las portadas no los muestran).
- **Cupo de informes del jugador:** cada informe pedido suma uno al contador del usuario (`reports_requested`) y queda
  registrado con su fecha (`requested_report`, V23). El jugador puede pedir hasta 5 por mes calendario (el cupo de su
  plan); el sexto responde `429` con el día en que se renueva (el 1 del mes siguiente). Un pedido rechazado (por
  ejemplo, un período inválido) no cuenta, y los de ejemplo tampoco. La pantalla del jugador muestra "3 de 5 informes
  este mes · se renueva el 1/11" (`GET /api/session` trae `monthlyReports`: `used`, `limit` y `renews`). El club no
  tiene límite.
- **Cuentas por producto:** cada cuenta es de un producto. Las de **RinkReports** (Club y Jugador) se crean en
  `/rinkreports/signup.html` e ingresan por `/rinkreports/login.html`. Las de **RinkMetrics** (DT) se crean en
  `/rinkmetrics/signup.html` e ingresan por `/rinkmetrics/login.html`. Una cuenta no ingresa por el otro producto: el login responde 401
  `OTHER_PRODUCT` con el ingreso que le corresponde. El mismo email y el mismo usuario pueden tener una cuenta en cada
  producto (únicos dentro del producto: el email desde V22 y el usuario desde V25). Cada cuenta queda asociada a su
  producto: el ingreso busca el usuario en el producto de la pantalla (`LoginProductFilter`) y la sesión y
  "Recordarme" la nombran con los dos (`REPORTS:juan`). Los mails (confirmación y clave nueva) llevan
  el nombre, el logo y el color del producto de la cuenta, y sus enlaces llevan a su ingreso.
- **Registro** (`/rinkreports/signup.html` o `/rinkmetrics/signup.html`): usuario, clave (mínimo 8, se guarda con BCrypt),
  email y club, obligatorios.
  No pide nombre ni apellido: el club no los carga y el jugador los toma de la FPP (tal como los separa el acta).
  Para Jugador FPP es obligatorio el **jugador**, que se elige de la planilla de un equipo del club
  (club → categoría → nombre). La cuenta queda vinculada al `id_player` de la FPP, y cada jugador tiene un solo
  usuario.
- **DT** (RinkMetrics, en vivo): elige su club y su plan; sus equipos y sus jugadores los arma después, en la
  sección Equipo (el registro ya no los pide). Entra a `/coach.html`, la pantalla de RinkMetrics (sin equipos, empieza
  por Equipo). Empieza con el plan free (`COACH_FREE`: hasta 3 equipos y 5 partidos por mes, sin costo hasta el 31/12/2026);
  el mensual (`COACH_MONTHLY`: hasta 3 equipos y 25 partidos por mes) figura como "Próximamente" y no se puede elegir
  mientras `SIGNUP_COACH_PAID` (`madsports.signup.coach-paid`) sea `false`, el valor por defecto (`max_teams` y `matches_per_month` en
  `subscription_plan`, V17).
- **DT de otra federación** (`COACHOTR`, "Otra" en el registro de RinkMetrics): para quien no es de la FPP ni de la
  AMP. Como no hay datos en SIDGAD, escribe el nombre de su club en lugar de elegirlo, y en la sección Equipos crea
  sus equipos con un nombre y carga a mano sus jugadores (nombre, apellido, número y si es arquero), que también
  corrige o da de baja. Sus partidos quedan con la federación `OTR`.
- **RinkMetrics (servidor):** cada partido es de un equipo del DT; el rival se escribe (con los ya usados para
  elegir), con la fecha, si es local o visitante, los minutos de cada tiempo (25 si no se eligen) y el campeonato. El
  mismo rival en la misma fecha retoma el partido en vez de crear otro. Un partido se puede eliminar con todas sus
  acciones (deja de contar en las estadísticas y los informes). Cada partido nuevo cuenta para
  el cupo del mes de suscripción (suscripto el 15: del 15 al 14). El reloj cuenta hacia atrás cada tiempo (25:00 a
  00:00, o los minutos elegidos) y se
  calcula con la hora de inicio y los segundos acumulados, así un teléfono bloqueado no pierde el minuto. Cada acción
  es de un jugador del plantel del equipo (el de la sección Equipo) y guarda el tiempo y el
  segundo jugado; su id lo genera el teléfono, así reenviar una tanda sin señal no duplica. El marcador se arma con
  las acciones. Tablas `coach_match` y `coach_action` (V12), `coach_player` (V15) y `coach_team` con
  `coach_player_team` (V16: los equipos de antes pasaron con su nombre y sus partidos; los jugadores traídos de SIDGAD
  salieron, y sus acciones conservan el nombre). Un partido cerrado se puede reabrir durante 48 h.
- **RinkMetrics: menú, panel, estadísticas, informes y plantel.** La pantalla del DT tiene un menú Control panel ·
  Partidos · Equipo · Estadísticas (submenú Equipo y Jugadores) · Informes (entra al Control panel).
  - **Control panel (dashboard general):** partidos cargados y del mes; estado de los partidos (programados = creados con fecha
    futura y sin iniciar, en juego, finalizados); partidos jugados por mes (los últimos 6) y los próximos partidos.
  - **Estadísticas > Equipo:** las estadísticas consolidadas de todos los partidos cargados del equipo (las mismas métricas del
    informe), el balance de los finalizados (jugados, ganados, empatados, perdidos y goles), los goleadores (los 5
    con más goles) y el top 3 en efectividad de penales y de libres a favor (a igual efectividad, el que más pateó).
  - **Estadísticas > Jugadores:** la ficha de cada uno, con índice (0 a 100) y un pentágono de cinco ejes comparados con el plantel
    (100 = el mejor del equipo): ataque, precisión, desequilibrio (faltas recibidas), recuperación (balance con las
    pérdidas) y disciplina. Los arqueros se comparan entre sí con sus ejes: atajadas, efectividad, pelota parada,
    valla y disciplina. Todo se calcula en el navegador (`js/coach/player-cards.js`) con `GET /api/coach/actions` (las acciones
    activas de todos los partidos del equipo), el plantel y la lista de partidos; los gráficos son SVG propios.
  - **Informes:** la lista de los partidos finalizados (terminados o cerrados), cada uno con su informe, y los
    informes de un mes, un bimestre, un semestre o un campeonato (el que se carga, opcional, al crear el partido; los
    que no tienen van a "Sin campeonato"). Cada informe trae goles, goles recibidos, tiros y su efectividad,
    atajadas, pérdidas, recuperaciones, faltas recibidas y cometidas, tarjetas azul, amarilla y roja, y la
    efectividad de penales y libres; la tabla por jugador y, en el de un partido, la lista de eventos (minuto,
    jugador y acción). Se puede elegir un jugador para ver solo sus estadísticas. Con conexión, la pantalla muestra
    el informe del servidor (`GET /api/coach/matches/{id}/report` y `GET /api/coach/reports`: el mismo JSON del PDF,
    `CoachReportService`, `CoachReport`, plantilla `templates/coach-report.html`); sin señal, el que se arma en el navegador
    (`js/coach/report.js`). Los dos cálculos se prueban contra los mismos casos (ver [Tests](#tests)).
  - **Equipo:** el ABM de equipos (hasta los del plan; la lista muestra los partidos finalizados jugados, ganados,
    empatados y perdidos; un equipo con partidos no se borra) y el ABM de jugadores: nombre, apellido, número, si es
    arquero y en qué equipos juega (uno o más). Un equipo nuevo se arma desde una **categoría del club** en SIDGAD
    (`GET /api/coach/club/categories`, las del club con el que se registró el DT): su plantel (`GET /api/coach/club/squad`,
    los jugadores de las actas del equipo, con su número más usado y si es arquero) viene tildado y el DT destilda a
    los que no van. Esos quedan como **no incluidos** en el equipo: figuran en su lista de jugadores, no aparecen para
    cargar acciones y se suman después con "Incluir" (`POST /api/coach/players/{id}/teams/{team}`). Un jugador que
    el DT ya tiene en otro equipo no se duplica (lo identifica su id de SIDGAD). También se puede crear un equipo de
    nombre libre ("Otro"), sin jugadores de SIDGAD, y cargar jugadores a mano. El número no se repite dentro de un
    equipo. Un jugador dado de baja ya no
    aparece para cargar acciones, pero las suyas quedan en los partidos y los informes, que muestran el nombre
    corregido.
- **RinkMetrics (pantalla, `/coach.html`):** módulos ES sin build en `js/coach/`: `main.js` (menú y arranque),
  `panel.js`, `matches.js`, `match.js` (la carga en vivo), `stats.js`, `reports.js`, `team.js`, y los
  compartidos `actions.js` (botones y reglas de goles), `report.js`, `player-cards.js`, `queue.js` y `common.js`; en la
  demo, `demo.js` y `demo-api.js`. Al volver
  a la pestaña se vuelven a pedir los equipos (por si cambiaron en otro dispositivo). En el celular va en una columna; en tablet y web (desde 760 px de ancho)
  la carga va en columnas (jugadores y últimas acciones | acciones, y en web las últimas acciones en una tercera
  columna) y entra entera en la pantalla, sin bajar.
  - **Partidos:** equipo, rival (escrito, con los ya usados para elegir), fecha, local / visitante, minutos por
    tiempo y campeonato (opcional, con los ya usados), y la lista de partidos cargados, cada uno con "Eliminar".
  - **Marcador y reloj fijos arriba:** iniciar, pausar / reanudar, fin del 1T, iniciar 2T, fin del partido y ajuste
    de ±1′ y ±10″. Al llegar a 00:00 avisa y vibra. Mientras el reloj corre, la pantalla no se apaga (Wake Lock, si
    el teléfono lo permite).
  - **Carga:** se toca el jugador y después la acción (dos toques). Las del arco (atajada, gol recibido, libre y
    penal en contra) van al arquero en cancha. Se pueden agregar jugadores que no figuran en las actas. Cada carga
    muestra "Deshacer" por 4 segundos, y las últimas acciones se pueden borrar.
  - **Informe:** el informe del partido en vivo (con lo que todavía no se envió), del equipo o de un jugador, con
    los eventos y "Exportar PDF"; y el cierre del partido (o reabrirlo). Al tocar "Fin del partido", la pantalla pasa
    sola a esta pestaña.
  - **Sin señal:** cada acción y cada borrado se guardan primero en el teléfono (IndexedDB, `js/coach/queue.js`) y se
    mandan en tandas cada 5 segundos o al volver la conexión; arriba se ve "Sin enviar: N". Un service worker
    (`coach-sw.js`) guarda la última copia de la página y de los datos, así la pantalla abre aunque se recargue sin
    señal. El reloj (iniciar, pausar, fin del tiempo) y el cierre sí necesitan conexión.
- **Confirmar el email:** al registrarse llega un mail con un enlace (vale 48 h). Hasta confirmarlo no se puede
  ingresar: el login avisa y ofrece reenviar el mail.
- **Recuperar la clave:** "¿Olvidaste tu clave?" en el ingreso manda un enlace de un solo uso que vale 1 h
  (`/reset-password.html`). Para no revelar qué emails están registrados, pedirlo siempre responde lo mismo. Se manda
  un mail por minuto como mucho, y en la base queda solo el hash del token.
- **Sesión:** dura 8 h sin actividad (`SESSION_TIMEOUT`). En el ingreso, "Mantener la sesión iniciada" (marcado por
  defecto) deja el dispositivo recordado 30 días (cookie `REMEMBER_ME`, token en `persistent_logins`, V13): si la
  sesión se pierde porque venció, se cerró el navegador o se reinició la app, el próximo pedido vuelve a entrar solo.
  El token cambia en cada uso y se borra al salir o al cambiar la clave. Si la sesión vence con una pantalla abierta,
  la web lleva al ingreso con el aviso "Tu sesión venció" y después vuelve a esa misma pantalla.
- **Plan en el registro:** cada perfil empieza con su plan free y tiene un plan mensual, sin permanencia (por ahora
  "Próximamente"). Al elegir el perfil se muestra
  con su precio, su cupo y los beneficios. Es obligatorio y queda como suscripción del usuario desde el día del
  registro. Todavía no hay cobro; el jugador tiene un cupo de informes por mes (ver arriba).
- **Precios en pesos:** el precio base de cada plan está en dólares y se muestra en pesos al dólar oficial del Banco
  Nación (venta) del día, que publica [DolarAPI](https://dolarapi.com), redondeado hacia abajo a centenas. Se consulta como mucho
  una vez por hora; si DolarAPI no responde se usa la última cotización o el respaldo (`DOLLAR_FALLBACK`, $ 1.500).

  | Plan mensual | Precio base | Incluye |
  |---|---|---|
  | Club (`CLUB_MONTHLY`) | U$S 20 | 8 informes por mes |
  | Jugador (`PLAYER_MONTHLY`) | U$S 7 | 4 informes por mes |
  | DT free (`COACH_FREE`) | Gratis hasta el 31/12/2026 | Hasta 3 equipos y 5 partidos por mes |
  | DT (`COACH_MONTHLY`) | U$S 12 | Hasta 3 equipos y 25 partidos por mes |

- **Suscripciones:** la tabla `subscription_plan` (con `price_usd`, `reports_per_month`, `max_teams` y
  `matches_per_month`) tiene un plan mensual por tipo de usuario: `PLAYER_MONTHLY`, `CLUB_MONTHLY` y `COACH_MONTHLY`, y el DT además el
  `COACH_FREE` (V14), con fecha fija de fin (`valid_until`): después del 31/12/2026 no se ofrece ni deja empezar
  partidos. `GET /api/plans` lista los vigentes, del más barato al más caro. A un
  usuario solo se le asigna el de su perfil, con su fecha desde y su fecha hasta (un mes). También lleva `reports_requested`, que suma uno cada vez que pide un informe de club o de
  jugador y el pedido se acepta (los rechazados y los de ejemplo no cuentan). `GET /api/session` devuelve las dos cosas.
  El jugador tiene un cupo de informes por mes (ver arriba); el DT, de equipos y partidos.
- **Home de Mad Sports** (`/`): pública. Mad Sports es la compañía que desarrolla aplicaciones de métricas,
  estadísticas e informes deportivos; la home presenta sus dos productos y cada tarjeta abre el suyo: **RinkReports**
  (`/rinkreports`, informes de jugador y de club) y **RinkMetrics** (`/rinkmetrics`, métricas en vivo para el DT).
  `/madsports` (la dirección vieja) lleva a la home.
- **Club FPP** (`/rinkreports/club.html`): la pantalla de informes del club, con el club fijo en el del usuario. La API rechaza pedidos
  de otro club (403), y cada informe solo lo ve y lo baja quien lo pidió.
- **Jugador FPP** (`/rinkreports/player.html`): muestra club, nombre y apellido, y jugador en la FPP (no editables). Pide año y
  período (Apertura, Clausura o Apertura + Clausura) y genera un PDF **agrupado por categoría**. Empieza con los
  totales y una tabla que compara las categorías. Sigue un bloque por cada categoría en que jugó, con todas sus
  estadísticas, y si jugó en más de una, cierra con la suma de todas. Cada bloque trae: partidos jugados, ganados, empatados y perdidos; goles; tarjetas amarillas, azules y rojas por
  separado, cada una con su promedio por partido; y penales y libres directos (convertidos, tirados y efectividad,
  con donas). Además, goles y tarjetas por tramos de 5 minutos (gráfico de columnas, con las tarjetas apiladas por
  color) y el detalle partido a partido.
- **Fortalezas y debilidades del jugador:** van por cada categoría en que jugó, dentro de su bloque y contra sus
  compañeros de esa categoría. Si jugó en más de una, al final aparecen las generales. Usan el mismo criterio que el
  informe de equipo (±10% de la
  referencia es "en línea"; con menos de 3 casos, "muestra chica"). Compara contra sus compañeros en los mismos
  partidos, según las planillas: goles por partido, titularidad, tarjetas por partido, azules y rojas, penales y
  libres convertidos. También compara la efectividad del equipo con él y sin él, y los goles recibidos durante sus
  sanciones contra el resto del tiempo.
- **Categorías en que puede jugar:** el jugador puede subir de categoría, nunca bajar. En la FPP, Infantil juega en
  Infantil, Prejuvenil y Juvenil; Prejuvenil, en Prejuvenil, Juvenil y Juniors; Juvenil, en Juvenil, Juniors, Reserva
  y Primera; Juniors, en Juniors, Reserva y Primera; Reserva y Primera, en Reserva y Primera. En la AMP la regla es la
  misma sobre sus categorías (la suya y dos arriba; desde Juvenil, hasta Senior).
  - **Su categoría real** es la más baja en que jugó en el año. En el registro se lo elige de la planilla de
    cualquier equipo en que figura, y esa categoría no tiene por qué ser la suya: un Juvenil que juega en Primera
    puede aparecer en el plantel de Primera.
  - **Cómo la busca el informe:** revisa todas las categorías a las que podría pertenecer. Por ejemplo, quien figura
    en Primera puede ser Juvenil, Juniors, Reserva o Primera. Con eso determina su categoría real y se queda con las
    categorías en que puede jugar, en su rama, y el informe lo aclara en una nota. Como se calcula cada año, también
    lo sigue cuando cambia de categoría.

El informe del jugador sale de las **actas**: cada acta trae la planilla de jugadores de cada equipo (primero el local),
con `id_player`, goles, tarjetas, penales y libres directos. Un partido cuenta si el jugador figura en la planilla
de su equipo. El minuto de goles y tarjetas sale de los eventos del acta. Si la planilla anota algo que no tiene
evento (pasa con algunas actas), va a "Sin minuto registrado". La tabla de
estadísticas de la FPP no alcanza, porque solo lista a los que hicieron goles. Se leen las actas de todos los equipos
del club en el período y en esas categorías (la primera vez tarda unos 10 s; después quedan en cache 24 h).

**Partidos sin acta:** SIDGAD no publica el acta de todos los partidos, pero los lista en la **ficha de temporada**
del jugador con sus goles, azules, rojas, penales y libres. Esos partidos cuentan como jugados, marcados con `*` en el
partido a partido; sus goles y tarjetas van a "Sin minuto registrado" (la ficha no trae amarillas, titularidad ni
minutos). Un partido sin acta que no figura en su ficha se toma como no jugado.

Los usuarios se guardan en H2 en archivo (`./data`, fuera de git); el esquema lo crea Flyway con las migraciones de
su motor (`db/migration/h2`). En producción la base es PostgreSQL (`db/migration/postgresql`), con `MADSPORTS_DB_URL`
apuntando a ella: cada cambio de esquema va en las dos carpetas con el mismo número. Ver
[docs/produccion.md](docs/produccion.md).

**Límite de intentos:** 5 ingresos fallidos por usuario y 20 por IP en 15 minutos; 5 altas de cuenta y 10 pedidos de
mails (confirmación, recuperar la clave) por IP por hora. Al llegar al máximo la API responde **429** con
`Retry-After` hasta que vence la ventana; un ingreso correcto limpia los fallidos del usuario.

## Correr

**En producción:** un servidor con Docker (la app, PostgreSQL y Caddy con HTTPS para los tres dominios): `Dockerfile`, carpeta `deploy/` y la guía paso a paso [docs/despliegue-donweb.md](docs/despliegue-donweb.md).

Requiere JDK 21. Los secretos locales (SMTP de Resend, Gemini) van en un `.env` junto al proyecto, fuera de git;
ver `.env.example`. En producción se cargan como variables de entorno.

Los mails (confirmar el email, elegir una clave nueva) salen en HTML con la identidad de la web (`templates/mail/`)
y el logo embebido, más una versión en texto. Con Resend: host `smtp.resend.com`, usuario `resend` y la API key
como clave (alcanza con permiso de solo envío).

```bash
export GEMINI_API_KEY=...   # opcional: sin key se usa el análisis por reglas
./mvnw -DskipTests install       # compila los módulos (una vez, o después de cambiar alguno)
./mvnw -pl app spring-boot:run
```

O con el jar: `./mvnw -DskipTests package` y `java -jar app/target/madsports-app-0.1.0-SNAPSHOT.jar`, desde la raíz
del proyecto (la base local queda en `./data`; `spring-boot:run` también arranca en la raíz). En IntelliJ, la clase
principal es `com.madsports.MadSportsApplication`, del módulo `madsports-app`, con el directorio de trabajo en la raíz.

Abrir http://localhost:8080.

| Variable | Default | |
|---|---|---|
| `GEMINI_API_KEY` | — | Habilita el análisis con Gemini |
| `AI_MODEL` | `gemini-3.8-flash` | Modelo de Gemini (con plan gratuito) |
| `AI_ENABLED` | `true` | `false` fuerza el análisis por reglas |
| `MADSPORTS_DB_URL` | `jdbc:h2:file:./data/madsports` | Base de usuarios |
| `MADSPORTS_DB_USER` / `MADSPORTS_DB_PASSWORD` | `sa` / vacío | Credenciales de la base |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | vacío, `587` | SMTP para los mails (Resend, Brevo, Gmail...). Sin `SMTP_HOST`, los mails se escriben en el log con su enlace |
| `MAIL_FROM` | `MadSports <info@madsports.com.ar>` | Remitente (tiene que ser un dominio verificado en el proveedor). El nombre lo pone cada mail: el del producto de la cuenta |
| `APP_BASE_URL` | `http://localhost:8080` | Dirección pública de la web, para los enlaces de los mails |
| `SIDGAD_WARMUP_CRON` | `0 0 5 * * *` | Precarga de madrugada (hora argentina; `-` la apaga): las actas nuevas del año y las fichas de temporada de los jugadores, para que el primer informe del día ya encuentre todo leído |
| `SIDGAD_WARMUP_FEDERATIONS` | `FPP` | Federaciones que precarga (`FPP,AMP`) |
| `REPORTS_DIR` | `./data/informes` | Carpeta donde se guardan los informes generados (PDF, Excel y HTML de cada pedido) para verlos y bajarlos desde el historial. En el servidor, un volumen |
| `REPORTS_RETENTION_MONTHS` | `12` | Meses que se conservan los informes guardados; todos los días a las 4:30 se borran los más viejos (el pedido queda en el historial) |
| `APP_URL_MADSPORTS`, `APP_URL_RINKREPORTS`, `APP_URL_RINKMETRICS` | vacío (`APP_BASE_URL`) | Un dominio por sitio (`https://madsports.com.ar`, `https://rinkreports.com.ar`, `https://rinkmetrics.com.ar`): la raíz de cada producto es su portada, sus páginas pedidas en otro dominio llevan al suyo y los mails enlazan al del producto de la cuenta. Ver `docs/produccion.md` |
| `DOLLAR_URL` | DolarAPI, dólar oficial | De dónde sale el tipo de cambio para los precios en pesos |
| `DOLLAR_FALLBACK` | `1500` | Pesos por dólar si DolarAPI nunca respondió |
| `SESSION_TIMEOUT` | `8h` | Sesión sin actividad |
| `SIGNUP_CLUB` | `false` | Habilita el alta de cuentas de club (las de jugador y DT están abiertas) |
| `SIGNUP_PLAYER_PAID` | `false` | Habilita elegir el plan Jugador mensual (pago) |
| `SIGNUP_AMP` | `false` | Ofrece la AMP en el registro (oculta hasta nuevo aviso; las cuentas de la AMP que ya existen siguen andando) |
| `SIGNUP_PLAYER_AMP` | `false` | Habilita el alta de jugadores de la AMP (por ahora, solo de la FPP) |
| `SIGNUP_COACH_PAID` | `false` | Habilita elegir el plan DT mensual (pago) |
| `REMEMBER_ME_DAYS` | `30` | Días que queda recordado un dispositivo |
| `REMEMBER_ME_KEY` | al azar | Clave de la sesión que arma "Recordarme" (no hace falta fijarla) |
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` detrás de un proxy: IP real para el límite de intentos |
| `LIMIT_LOGIN_USER`, `LIMIT_LOGIN_IP`, `LIMIT_SIGNUP_IP`, `LIMIT_MAILS_IP` | `5`, `20`, `5`, `10` | Límite de intentos |
| `ALERTS_EMAIL` | vacío | Recibe las alertas del chequeo diario de SIDGAD (vacío: solo log) |
| `SIDGAD_MONITOR_CRON` | `0 0 8 * * *` | Hora del chequeo diario de SIDGAD (hora argentina; `-` lo apaga) |

Las variables de antes (`FPP_DB_URL`, `REGISTRO_*`, `LIMITE_*`, `RECORDARME_*`, `DOLAR_*`, `ALERTAS_EMAIL`,
`MONITOR_SIDGAD_CRON`, `FPP_AI_*`) siguen valiendo como respaldo de las nuevas.

**Health check:** `GET /actuator/health` (público, solo UP o DOWN) que tiene en cuenta la base y el último chequeo
diario de SIDGAD; `GET /actuator/health/liveness` para la sonda del proveedor.

La configuración propia va bajo `rinkmetrics:` en `application.yml` (clase `RinkMetricsProperties`).

**API key gratuita:** se crea en Google AI Studio (https://aistudio.google.com/apikey) con una cuenta de Google.
En el plan gratuito Google puede usar el contenido enviado para mejorar sus productos (son datos deportivos
públicos de la FPP y la AMP) y hay límites de pedidos por minuto y por día; los modelos y límites cambian seguido.

Categorías relevadas (de Infantil para arriba), en `Federation`:

| Federación | Categorías |
|---|---|
| FPP | Infantil, Prejuvenil, Juvenil, Juniors, Reserva, Primera |
| AMP | Infantil, Precadete, Cadete, Prejuvenil, Juvenil, Prejunior, Junior, Reserva, Senior (en femenino: Infantil, Cadete, Juvenil, Junior, Senior) |

Las dos usan la misma regla para saber en qué categorías puede jugar cada jugador: la suya y las dos de arriba; desde
Juvenil, hasta la mayor (Primera o Senior); Reserva y la mayor, entre sí. La categoría del jugador es la más baja en
que jugó en el año.

Cada análisis con Gemini deja en el log el consumo de tokens (`Consumo de Gemini para ...`).

## API

Públicos: registro, sesión, catálogo e informes de ejemplo. El resto pide sesión con el perfil que corresponde
(sin sesión, `401`; con otro perfil, `403`). Los POST llevan el header `X-XSRF-TOKEN` con el valor de la cookie.

- `POST /api/signup` `{"profile":"PLAYERFPP","username":"...","password":"...","email":"...","clubId":22,"category":"PRIMERA","branch":"MASCULINO","playerId":5973,"subscription":"PLAYER_MONTHLY"}` → `201`
- `GET /api/signup/players?clubId=22&category=PRIMERA&branch=MASCULINO&federation=FPP`: plantel del equipo según
  sus actas (`federation` FPP o AMP; sin ella, FPP)
- `POST /api/signup` de un DT: `"profile":"COACHFPP"` y `"subscription":"COACH_MONTHLY"` (sin equipos)
- `GET /api/plans?profile=CLUBFPP` (o `PLAYERFPP`, `COACHAMP`...): los planes del perfil, con el precio en
  pesos (y el precio base en dólares, la cotización usada y su fecha), los informes por mes y, para el DT, los
  partidos por mes
- `POST /api/login` (form: `username`, `password`, `product`, `rememberMe`) → `{home}`; `POST /api/logout`
- `GET /api/session`: usuario, perfiles, federación, club, jugador, producto y la suscripción
  (`authenticated: false` sin sesión)
- `GET /api/verify?token=...`: enlace del mail; confirma el email y lleva al ingreso.
  `POST /api/verification/resend` `{"email"}` o `{"username"}`
- `POST /api/password/forgot` `{"email","product"}` (`product`: `REPORTS` o `METRICS`, el de la pantalla);
  `GET /api/signup/options` → `{club, amp, playerAmp}` (qué perfiles se pueden registrar hoy); `POST /api/password/reset` `{"token","password"}`
- `POST /api/player/reports` `{"year":2026,"period":"SEASON"}` → `202 {id, status}`; `GET /api/player/reports/{id}` y `/html`, `/pdf` y `/xlsx` (PLAYERFPP o PLAYERAMP)
- RinkMetrics (COACHFPP, COACHAMP o COACHOTR; sin `team`, el primer equipo del DT; lo de otro DT da 404):
  - `GET /api/coach/teams`: los equipos con `played`, `won`, `drawn` y `lost` (partidos finalizados);
    `POST /api/coach/teams` `{"name"}` → `201` (`409` si ya tiene los equipos del plan); `PUT /api/coach/teams/{id}`; `DELETE /api/coach/teams/{id}` → `204`
    (`409` si tiene partidos)
  - `GET /api/coach/players`: todos los jugadores con sus `teams`; `POST /api/coach/players`
    `{"name","lastName","number","goalkeeper","teams":[1,2]}` → `201` (id desde 900000000); `PUT /api/coach/players/{id}`
    (mismo cuerpo); `DELETE /api/coach/players/{id}` → `204` (baja: sus acciones quedan). `409` si el número ya es
    de otro jugador del mismo equipo.
  - `GET /api/coach/squad?team=1`: los jugadores activos del equipo (`id`, `number`, `name` completo, `names`,
    `lastName`, `goalkeeper`, `teams`)
  - `GET /api/coach/actions?team=1`: acciones activas de todos los partidos del equipo (con `matchId`), para las fichas
  - `POST /api/coach/matches` `{"team":1,"opponent":"HURACAN","date":"2026-10-18","home":false,"tournament":"Apertura","minutes":20}`
    → `201` (sin `date`, hoy; sin `minutes`, 25; `tournament` opcional); con el mismo rival y fecha devuelve el ya
    empezado; `409` si se acabó el cupo del mes; `DELETE /api/coach/matches/{id}` → `204` lo elimina con sus acciones
  - `GET /api/coach/matches`, `GET /api/coach/matches/{id}` (con `clock {period, elapsed, remaining, running}` y las
    acciones)
  - `POST /api/coach/matches/{id}/actions` `[{"id":"<uuid>","playerId":11,"kind":"SHOT","outcome":"GOAL","period":1,"atSecond":390}]`
    (`kind`: `TURNOVER`, `RECOVERY`, `SHOT`, `SAVE`, `GOAL_CONCEDED`, `FREE_HIT_FOR`, `FREE_HIT_AGAINST`,
    `PENALTY_FOR`, `PENALTY_AGAINST`, `FOUL`, `CARD`); `DELETE /api/coach/matches/{id}/actions/{actionId}` deshace
  - `POST /api/coach/matches/{id}/clock` `{"action":"START"}` (`PAUSE`, `RESUME`, `END_PERIOD`, o `ADJUST` con
    `seconds`); `POST /api/coach/matches/{id}/close` y `/reopen`
  - `GET /api/coach/matches/{id}/report?player=j11` y `/report.pdf`: informe del partido (`player`: "j" + id del
    plantel, o "m" + nombre de un jugador agregado a mano; sin él, todo el equipo)
  - `GET /api/coach/reports?team=1&period=MONTH&value=2026-10&player=` y `/api/coach/reports.pdf`: informe de los
    partidos finalizados del período (`period`: `MONTH` "2026-10", `TWO_MONTHS` "2026-5" = septiembre-octubre,
    `SEMESTER` "2026-2", `CHAMPIONSHIP` con el nombre); `400` si no hay partidos finalizados
- `GET /api/catalog?year=2026&federation=AMP` — clubes con sus categorías y ramas del año en la federación (sin
  `year`: el más reciente con datos; sin `federation`: FPP); `available: false` y `message` si el año no tiene datos
- `GET /api/sample.pdf` — informe modelo del club completo con datos ficticios ("Club Ejemplo": Primera y Juvenil
  masculinas, Infantil femenina), marcado en cada página; no consulta
  SIDGAD ni la API de Gemini. También `GET /api/sample.html` (para ver en pantalla) y `GET /api/sample.xlsx`.
- `GET /api/sample-player.pdf`: informe modelo de jugador con datos ficticios ("Tomás Ejemplo", de Juniors, que
  también juega en Primera del "Club Ejemplo"). Se arma con el mismo motor que los informes reales. Enlazado desde
  RinkReports. También `GET /api/sample-player.html` y `GET /api/sample-player.xlsx`.
- `POST /api/reports` (CLUBFPP o CLUBAMP, solo su club y su federación) `{"clubId":74,"category":"INFANTIL","branch":"FEMENINO","period":"SEASON","year":2026}` → `202 {id, status}`
  (`period`: `OPENING`, `CLOSING`, `SEASON` o `LAST_5`). Sin `category` ni `branch`
  (`{"clubId":74,"period":"SEASON","year":2026}`): el informe del club completo, por rama y categoría; con una
  sola de las dos, `400`
- `GET /api/reports/{id}` — estado: `PENDING`, `EXTRACTING`, `ANALYZING`, `RENDERING`, `READY`, `ERROR`
- `GET /api/reports/{id}/html` (el informe para ver en pantalla), `/pdf` y `/xlsx` (Excel); el estado listo trae
  `htmlUrl`, `pdfUrl` y `xlsxUrl`

## Arquitectura

El proyecto **madsports** es un multi-módulo Maven con los dos productos de Mad Sports en carpetas separadas. Cada
módulo solo usa los de abajo: Maven no compila un import de un módulo que no declaró.

| Módulo (carpeta) | Paquetes (`com.madsports.*`) | Qué tiene | Usa |
|---|---|---|---|
| `hockey-data` | `sidgad`, `catalog`, `stats` | SIDGAD, clubes y categorías, planteles (`RosterService`, `ClubTeams`) y estadísticas | — |
| `platform` | `account`, `config`, `pdf` | Cuentas, seguridad, mails, planes, plantillas a PDF (`Templates`); `app.css`, `app.js`, ingreso y registro compartidos, mails | `hockey-data` |
| `rinkreports` | `report`, `player`, `analysis` | Informes de club y de jugador, Excel, análisis con IA, cupo de informes; `/rinkreports/**` | `platform` |
| `rinkmetrics` | `coach` | Partidos en vivo, equipos, planteles e informes del cuerpo técnico; `coach.html`, `js/coach`, su portada (`/rinkmetrics`) | `platform` |
| `app` (`madsports-app`) | `monitor` y `MadSportsApplication` | La aplicación: junta todo (un solo jar), `application.yml`, migraciones, la home de Mad Sports, el chequeo diario de SIDGAD y los tests | todos |

Cada módulo lleva su código y sus recursos (`static/`, `templates/`); la app los sirve juntos desde el classpath. Lo
que un producto aporta a la sesión, como el cupo de informes, entra por una interfaz de la plataforma
(`MonthlyReports`).

**Tests, cada uno en su módulo:**

| Módulo | Tests |
|---|---|
| `hockey-data` | Parsers y cliente de SIDGAD, catálogo, motor de estadísticas (con el HTML guardado en `src/test/resources/fixtures`, que publica como *test-jar* para los demás) |
| `platform` | Cotización del dólar |
| `rinkreports` | Informes de club y de jugador, PDF, Excel, ejemplos y análisis (los que usan Spring levantan solo lo de los informes: `ReportsTestConfig`, sin base ni seguridad) |
| `rinkmetrics` | Partido y acciones; las pantallas en `src/test/js` (node:test) y `src/test/e2e` (Playwright) |
| `app` | Lo que necesita la aplicación completa: seguridad y cuentas, API de RinkMetrics y su contrato, API de los ejemplos, límites, migraciones y `ArchitectureTest` | `ArchitectureTest` (ArchUnit) controla
además las reglas entre paquetes: ningún producto usa el otro.

## Tests

```bash
./mvnw test     # unitarios (*Test), con H2 en memoria: no necesitan Docker
./mvnw verify   # unitarios + integración (*IT) contra PostgreSQL 16 real (Testcontainers; requiere Docker) + cobertura
npm install     # una vez (Node 22 o más nuevo): Playwright para los E2E
npm test        # módulos de js/coach y contrato de la API contra la demo (node:test, sin navegador)
npm run e2e     # E2E de la demo con Playwright (celular y escritorio; usa el Chrome instalado)
npm run demo    # la demo sin levantar la app: http://localhost:8099/coach-demo.html
```

| Suite | Plugin | Base | Qué cubre |
|---|---|---|---|
| `*Test` | surefire | H2 en memoria | Parsers contra fixtures, motor de estadísticas, informes, servicios y la API completa con MockMvc |
| `*IT` | failsafe | PostgreSQL 16 (un contenedor por clase) | `MigrationsIT` (las dos carpetas de migraciones tienen las mismas versiones, se aplican todas y el esquema queda con zona horaria, cascadas y únicos) y los flujos de la API sobre la base de producción: `SecurityIT`, `ReportsSignupIT`, `AttemptLimiterIT`, `CoachControllerIT`, `CoachApiContractIT` (heredan los casos de su `*Test`) |
| `rinkmetrics/src/test/js/*.test.mjs` | node:test | — | Los módulos de `js/coach` (períodos y métricas de los informes) y el contrato de la API contra la API simulada de la demo |
| `rinkmetrics/src/test/e2e/*.spec.mjs` | Playwright | — | La demo en un navegador (celular y escritorio): carga de acciones, deshacer, arquero en cancha, sin señal, fin del partido con su informe, Control panel, Partidos, Estadísticas, Informes, ABM de Equipo y equipos que cambian en otro dispositivo |

**Contrato de la API de RinkMetrics** (`app/src/test/resources/coach/api-contract.json`): escenarios de pedidos y respuestas
(equipos, jugadores, partido en vivo e informes, con las métricas calculadas a mano) que corren el servidor
(`CoachApiContractTest` con H2 y `CoachApiContractIT` con PostgreSQL) y la API simulada de la demo (`js/coach/demo-api.js`,
que arma los informes con `js/coach/report.js`, el mismo cálculo de la pantalla sin señal). Así la demo, la pantalla
sin señal y el servidor no se separan: un cambio en uno sin el otro rompe el CI.

La cobertura de JaCoCo suma las dos suites y queda en `app/target/site/jacoco-aggregate/index.html` (todos los módulos).

El CI de GitHub Actions (`.github/workflows/ci.yml`) corre en cada PR y en cada push a `main` dos jobs: **Backend**
(`./mvnw verify`: publica el resumen de cobertura en la corrida, el reporte como artefacto y, si algo falla, los
reportes de surefire y failsafe) y **Pantallas** (`npm test` y los E2E con el Chromium de Playwright; si fallan,
publica el reporte con trazas y capturas). Dependabot (`.github/dependabot.yml`) abre PRs semanales con las
dependencias de Maven, de npm y las acciones de GitHub, que valida el mismo CI.

Los parsers y el motor se prueban contra HTML real guardado en `app/src/test/resources/fixtures` (octubre de 2026:
actas, calendarios, posiciones, la ficha de temporada de un jugador y el catálogo 2026 con la FPP y la AMP). Si SIDGAD
cambia el markup, actualizar los fixtures y ver qué test falla. La seguridad, el registro y los planes se prueban con
MockMvc y H2 en memoria (el dólar de los tests es fijo: $ 1.500).

El proyecto usa **Maven 3.9.11** por el wrapper: IntelliJ 2025.3 no arranca su Maven con 3.10.0
(`No implementation for ArtifactHandlerManager was bound`). Con IntelliJ, usar "Maven wrapper" en la configuración
de Maven y recargar el proyecto.

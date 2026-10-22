<div align="center">

<img src="docs/img/logo.svg" width="96" alt="RinkMetrics">

# RinkMetrics

**Estadísticas de hockey sobre patines, convertidas en informes PDF.**

Informes de equipo y de jugador a partir de los datos de la Federación Porteña de Patín (FPP),
con gráficos, análisis minuto a minuto y texto redactado por IA.

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
- [Tests](#tests)
- [Producción](docs/produccion.md)

## Qué hace

| Función | Detalle |
|---|---|
| 🏒 **Informe de equipo** | Resultados, posiciones, goleadores (sin arqueros), pelota parada, disciplina, faltas, fortalezas y debilidades, con análisis escrito por IA (Google Gemini) o por reglas. |
| 👤 **Informe de jugador** | Agrupado por categoría: partidos (G/E/P), goles, tarjetas amarillas, azules y rojas, penales y libres directos, con promedios por partido, más fortalezas y debilidades contra sus compañeros. Cuenta también los partidos sin acta publicada. |
| 📋 **Métricas DT** | Perfil DT para cargar en vivo, desde el celular, las acciones de cada jugador durante el partido, aunque se corte la señal. |
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
`/rinkreports/ejemplo.html`, y en PDF y Excel en `GET /api/ejemplo.pdf|.xlsx` y `GET /api/ejemplo-jugador.pdf|.xlsx`.</sub>

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

Las direcciones anteriores (`/informes.html`, `/club.html`, `/jugador.html`) llevan a las nuevas.

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
| <img src="https://img.shields.io/badge/H2-0000BB?style=flat-square" alt="H2"> | Base de datos | **H2 2.3** en archivo (`./data`) | Desarrollo. En producción se configura otra base con `FPP_DB_URL` (por ejemplo PostgreSQL) |
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
| `cap_gr_<idp>_1.php` | Acta del partido: goles y tarjetas con período y minuto, y la planilla de cada equipo (con el arquero marcado "P") |
| `profiles/cap_profileseason_<id_player>_1_<temp>.php` | Ficha de temporada de un jugador: todos sus partidos de cada competición, también los sin acta, con goles, libres, penales, azules y rojas |

Federaciones (`Federacion`): **FPP** = `id_fede_3` (Federación Porteña de Patín) y **AMP** = `id_fede_4` (Asociación
Mendocina de Patín). Los fragmentos son los mismos para las dos; los nombres de las competiciones de la AMP tienen otro
formato (`AMP'26- CAMP MASC "APERTURA 26"-CATEGORIA SENIOR`, que se lee del texto visible porque las comillas cortan el
atributo `name`). El año se traduce a la temporada de SIDGAD (`temp_N`) con el selector de temporadas de la página
principal del portal (`a.select_temporada[id_temp][temp_name]`, p. ej. 40 = 2026), que es la misma para las dos
federaciones. Años ofrecidos: `rinkmetrics.sidgad.years` en `application.yml`. La tabla de posiciones de SIDGAD puede
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
  vivo del DT, y su plan). El menú "Informes / DT" está arriba en todas
  las páginas, salvo en la pantalla de Métricas DT (y su demo): ahí la barra superior lleva solo el menú del DT
  (Dashboard · Partidos · Jugadores · Informes) y el logo vuelve a la portada. El ingreso es uno solo y lleva a cada usuario a la pantalla de su perfil.
- **Por qué RinkMetrics** (`/por-que.html`, en el menú): las ventajas de la plataforma, en su propia página.
- **Demo de Métricas DT** (`/dt-demo.html`, en el menú "Demo"): Métricas DT completo con datos de ejemplo, sin
  cuenta: arranca en el Control panel y se recorren todas las pantallas (Partidos, con un partido en juego para cargar
  acciones; Equipo, con sus ABM; Estadísticas e Informes). Es la misma página que `/dt.html` (sin copia): en la demo,
  `js/dt/demo-api.js` contesta en el navegador las llamadas a la API, igual que el servidor (nada se guarda). La página de
  la sección muestra capturas de la demo ("Así se ve Métricas DT", en `img/demo/`); se regeneran con la app levantada:
  `node docs/capturas-demo.mjs` (usa el Chrome instalado).
- **Alta de cuentas de jugador y de DT; la de club, próximamente:** el registro de Informes
  (`/registro.html?seccion=informes`) crea cuentas de jugador de la FPP (las de la AMP, próximamente:
  `REGISTRO_JUGADOR_AMP`) y el de Métricas DT
  (`/registro.html?seccion=dt`), de DT. El alta de club está abierta (`REGISTRO_CLUB`,
  `rinkmetrics.registro.club`, en `true` por defecto; en `false`, el registro la muestra como "Próximamente").
- **Planes de RinkReports:** el **Jugador free** (sin costo hasta el 31/12/2026, 5 informes por mes) y el **Club
  free** (sin costo hasta el 31/12/2026, sin límite de informes). Los mensuales (pagos) figuran con el cintillo
  "Próximamente", apagados y borroneados, y con **"Precio a definir"**, mientras `REGISTRO_JUGADOR_PAGO` y
  `REGISTRO_CLUB_PAGO` (`rinkmetrics.registro.jugador-pago` y `club-pago`) sean `false`. Se muestran en `/rinkreports`
  y en el registro.
- **Cupo de informes del jugador:** cada informe pedido suma uno al contador del usuario (`informes_pedidos`) y queda
  registrado con su fecha (`informe_pedido`, V23). El jugador puede pedir hasta 5 por mes calendario (el cupo de su
  plan); el sexto responde `429` con el día en que se renueva (el 1 del mes siguiente). Un pedido rechazado (por
  ejemplo, un período inválido) no cuenta, y los de ejemplo tampoco. La pantalla del jugador muestra "3 de 5 informes
  este mes · se renueva el 1/11" (`GET /api/sesion` trae `informesMes`: `usados`, `limite` y `renueva`). El club no
  tiene límite.
- **Cuentas por producto:** cada cuenta es de un producto. Las de **RinkReports** (Club y Jugador) se crean en
  `/rinkreports/registro.html` e ingresan por `/rinkreports/login.html`. Las de **RinkMetrics** (DT) se crean en
  `/registro.html` e ingresan por `/login.html`. Una cuenta no ingresa por el otro producto: el login responde 401
  `OTRO_PRODUCTO` con el ingreso que le corresponde. El mismo email puede tener una cuenta en cada producto (único
  dentro del producto, V22); el usuario sigue siendo único en toda la web. Los mails (confirmación y clave nueva) llevan
  el nombre, el logo y el color del producto de la cuenta, y sus enlaces llevan a su ingreso.
- **Registro** (`/rinkreports/registro.html` o `/registro.html`): usuario, clave (mínimo 8, se guarda con BCrypt),
  email y club, obligatorios.
  No pide nombre ni apellido: el club no los carga y el jugador los toma de la FPP (tal como los separa el acta).
  Para Jugador FPP es obligatorio el **jugador**, que se elige de la planilla de un equipo del club
  (club → categoría → nombre). La cuenta queda vinculada al `id_player` de la FPP, y cada jugador tiene un solo
  usuario.
- **DT** (Métricas DT, en vivo): elige su club y su plan; sus equipos y sus jugadores los arma después, en la
  sección Equipo (el registro ya no los pide). Entra a `/dt.html`, la pantalla de Métricas DT (sin equipos, empieza
  por Equipo). Empieza con el plan free (`DT_FREE`: 1 equipo y 5 partidos por mes, sin costo hasta el 31/12/2026);
  el mensual (`DT_MENSUAL`: hasta 3 equipos y 25 partidos por mes) figura como "Próximamente" y no se puede elegir
  mientras `REGISTRO_DT_PAGO` (`rinkmetrics.registro.dt-pago`) sea `false`, el valor por defecto (`equipos_max` y `partidos_por_mes` en
  `suscripcion_type`, V17).
- **Métricas DT (servidor):** cada partido es de un equipo del DT; el rival se escribe (con los ya usados para
  elegir), con la fecha, si es local o visitante, los minutos de cada tiempo (25 si no se eligen) y el campeonato. El
  mismo rival en la misma fecha retoma el partido en vez de crear otro. Un partido se puede eliminar con todas sus
  acciones (deja de contar en las estadísticas y los informes). Cada partido nuevo cuenta para
  el cupo del mes de suscripción (suscripto el 15: del 15 al 14). El reloj cuenta hacia atrás cada tiempo (25:00 a
  00:00, o los minutos elegidos) y se
  calcula con la hora de inicio y los segundos acumulados, así un teléfono bloqueado no pierde el minuto. Cada acción
  es de un jugador del plantel del equipo (el de la sección Equipo) y guarda el tiempo y el
  segundo jugado; su id lo genera el teléfono, así reenviar una tanda sin señal no duplica. El marcador se arma con
  las acciones. Tablas `partido_dt` y `accion_dt` (V12), `jugador_dt` (V15) y `equipo_dt` con
  `jugador_equipo_dt` (V16: los equipos de antes pasaron con su nombre y sus partidos; los jugadores traídos de SIDGAD
  salieron, y sus acciones conservan el nombre). Un partido cerrado se puede reabrir durante 48 h.
- **Métricas DT: menú, panel, estadísticas, informes y plantel.** La pantalla del DT tiene un menú Control panel ·
  Partidos · Equipo · Estadísticas (submenú Equipo y Jugadores) · Informes (entra al Control panel).
  - **Control panel (dashboard general):** partidos cargados y del mes; estado de los partidos (programados = creados con fecha
    futura y sin iniciar, en juego, finalizados); partidos jugados por mes (los últimos 6) y los próximos partidos.
  - **Estadísticas > Equipo:** las estadísticas consolidadas de todos los partidos cargados del equipo (las mismas métricas del
    informe), el balance de los finalizados (jugados, ganados, empatados, perdidos y goles), los goleadores (los 5
    con más goles) y el top 3 en efectividad de penales y de libres a favor (a igual efectividad, el que más pateó).
  - **Estadísticas > Jugadores:** la ficha de cada uno, con índice (0 a 100) y un pentágono de cinco ejes comparados con el plantel
    (100 = el mejor del equipo): ataque, precisión, desequilibrio (faltas recibidas), recuperación (balance con las
    pérdidas) y disciplina. Los arqueros se comparan entre sí con sus ejes: atajadas, efectividad, pelota parada,
    valla y disciplina. Todo se calcula en el navegador (`js/dt/fichas.js`) con `GET /api/dt/acciones` (las acciones
    activas de todos los partidos del equipo), el plantel y la lista de partidos; los gráficos son SVG propios.
  - **Informes:** la lista de los partidos finalizados (terminados o cerrados), cada uno con su informe, y los
    informes de un mes, un bimestre, un semestre o un campeonato (el que se carga, opcional, al crear el partido; los
    que no tienen van a "Sin campeonato"). Cada informe trae goles, goles recibidos, tiros y su efectividad,
    atajadas, pérdidas, recuperaciones, faltas recibidas y cometidas, tarjetas azul, amarilla y roja, y la
    efectividad de penales y libres; la tabla por jugador y, en el de un partido, la lista de eventos (minuto,
    jugador y acción). Se puede elegir un jugador para ver solo sus estadísticas. Con conexión, la pantalla muestra
    el informe del servidor (`GET /api/dt/partidos/{id}/informe` y `GET /api/dt/informes`: el mismo JSON del PDF,
    `DtInformeService`, `InformeDt`, plantilla `templates/dt-informe.html`); sin señal, el que se arma en el navegador
    (`js/dt/informe.js`). Los dos cálculos se prueban contra los mismos casos (ver [Tests](#tests)).
  - **Equipo:** el ABM de equipos (hasta los del plan; la lista muestra los partidos finalizados jugados, ganados,
    empatados y perdidos; un equipo con partidos no se borra) y el ABM de jugadores: nombre, apellido, número, si es
    arquero y en qué equipos juega (uno o más). Un equipo nuevo se arma desde una **categoría del club** en SIDGAD
    (`GET /api/dt/club/categorias`, las del club con el que se registró el DT): su plantel (`GET /api/dt/club/plantel`,
    los jugadores de las actas del equipo, con su número más usado y si es arquero) viene tildado y el DT destilda a
    los que no van. Esos quedan como **no incluidos** en el equipo: figuran en su lista de jugadores, no aparecen para
    cargar acciones y se suman después con "Incluir" (`POST /api/dt/jugadores/{id}/equipos/{equipo}`). Un jugador que
    el DT ya tiene en otro equipo no se duplica (lo identifica su id de SIDGAD). También se puede crear un equipo de
    nombre libre ("Otro"), sin jugadores de SIDGAD, y cargar jugadores a mano. El número no se repite dentro de un
    equipo. Un jugador dado de baja ya no
    aparece para cargar acciones, pero las suyas quedan en los partidos y los informes, que muestran el nombre
    corregido.
- **Métricas DT (pantalla, `/dt.html`):** módulos ES sin build en `js/dt/`: `main.js` (menú y arranque),
  `panel.js`, `partidos.js`, `partido.js` (la carga en vivo), `estadisticas.js`, `informes.js`, `equipo.js`, y los
  compartidos `acciones.js` (botones y reglas de goles), `informe.js`, `fichas.js`, `cola.js` y `comun.js`. Al volver
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
  - **Sin señal:** cada acción y cada borrado se guardan primero en el teléfono (IndexedDB, `js/dt/cola.js`) y se
    mandan en tandas cada 5 segundos o al volver la conexión; arriba se ve "Sin enviar: N". Un service worker
    (`dt-sw.js`) guarda la última copia de la página y de los datos, así la pantalla abre aunque se recargue sin
    señal. El reloj (iniciar, pausar, fin del tiempo) y el cierre sí necesitan conexión.
- **Confirmar el email:** al registrarse llega un mail con un enlace (vale 48 h). Hasta confirmarlo no se puede
  ingresar: el login avisa y ofrece reenviar el mail.
- **Recuperar la clave:** "¿Olvidaste tu clave?" en el ingreso manda un enlace de un solo uso que vale 1 h
  (`/restablecer.html`). Para no revelar qué emails están registrados, pedirlo siempre responde lo mismo. Se manda
  un mail por minuto como mucho, y en la base queda solo el hash del token.
- **Sesión:** dura 8 h sin actividad (`SESSION_TIMEOUT`). En el ingreso, "Mantener la sesión iniciada" (marcado por
  defecto) deja el dispositivo recordado 30 días (cookie `RECORDARME`, token en `persistent_logins`, V13): si la
  sesión se pierde porque venció, se cerró el navegador o se reinició la app, el próximo pedido vuelve a entrar solo.
  El token cambia en cada uso y se borra al salir o al cambiar la clave. Si la sesión vence con una pantalla abierta,
  la web lleva al ingreso con el aviso "Tu sesión venció" y después vuelve a esa misma pantalla.
- **Plan en el registro:** cada perfil tiene un solo plan, mensual y sin permanencia. Al elegir el perfil se muestra
  con su precio, su cupo y los beneficios. Es obligatorio y queda como suscripción del usuario desde el día del
  registro. Todavía no hay cobro ni límite de informes por mes.
- **Precios en pesos:** el precio base de cada plan está en dólares y se muestra en pesos al dólar oficial del Banco
  Nación (venta) del día, que publica [DolarAPI](https://dolarapi.com), redondeado hacia abajo a centenas. Se consulta como mucho
  una vez por hora; si DolarAPI no responde se usa la última cotización o el respaldo (`DOLAR_RESPALDO`, $ 1.500).

  | Plan mensual | Precio base | Incluye |
  |---|---|---|
  | Club (`CLUB_MENSUAL`) | U$S 20 | 8 informes por mes |
  | Jugador (`PLAYER_MENSUAL`) | U$S 7 | 4 informes por mes |
  | DT free (`DT_FREE`) | Gratis hasta el 31/12/2026 | 1 equipo y 5 partidos por mes |
  | DT (`DT_MENSUAL`) | U$S 12 | Hasta 3 equipos y 25 partidos por mes |

- **Suscripciones:** la tabla `suscripcion_type` (con `precio_usd`, `informes_por_mes`, `equipos_max` y
  `partidos_por_mes`) tiene un plan mensual por tipo de usuario: `PLAYER_MENSUAL`, `CLUB_MENSUAL` y `DT_MENSUAL`, y el DT además el
  `DT_FREE` (V14), con fecha fija de fin (`vigente_hasta`): después del 31/12/2026 no se ofrece ni deja empezar
  partidos. `GET /api/suscripciones` lista los vigentes, del más barato al más caro. A un
  usuario solo se le asigna el de su perfil, con su fecha desde y su fecha hasta (un mes). También lleva `informes_pedidos`, que suma uno cada vez que pide un informe de club o de
  jugador y el pedido se acepta (los rechazados y los de ejemplo no cuentan). `GET /api/sesion` devuelve las dos cosas.
  El jugador tiene un cupo de informes por mes (ver arriba); el DT, de equipos y partidos.
- **Home de Mad Sports** (`/`): pública. Mad Sports es la compañía que desarrolla aplicaciones de métricas,
  estadísticas e informes deportivos; la home presenta sus dos productos y cada tarjeta abre el suyo: **RinkReports**
  (`/rinkreports`, informes de jugador y de club) y **RinkMetrics** (`/rinkmetrics`, métricas en vivo para el DT).
  `/madsports` (la dirección vieja) lleva a la home.
- **Club FPP** (`/rinkreports/club.html`): la pantalla de informes del club, con el club fijo en el del usuario. La API rechaza pedidos
  de otro club (403), y cada informe solo lo ve y lo baja quien lo pidió.
- **Jugador FPP** (`/rinkreports/jugador.html`): muestra club, nombre y apellido, y jugador en la FPP (no editables). Pide año y
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
su motor (`db/migration/h2`). En producción la base es PostgreSQL (`db/migration/postgresql`), con `FPP_DB_URL`
apuntando a ella: cada cambio de esquema va en las dos carpetas con el mismo número. Ver
[docs/produccion.md](docs/produccion.md).

**Límite de intentos:** 5 ingresos fallidos por usuario y 20 por IP en 15 minutos; 5 altas de cuenta y 10 pedidos de
mails (confirmación, recuperar la clave) por IP por hora. Al llegar al máximo la API responde **429** con
`Retry-After` hasta que vence la ventana; un ingreso correcto limpia los fallidos del usuario.

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
| `FPP_DB_URL` | `jdbc:h2:file:./data/rink-metrics` | Base de usuarios |
| `FPP_DB_USER` / `FPP_DB_PASSWORD` | `sa` / vacío | Credenciales de la base |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | vacío, `587` | SMTP para los mails (Resend, Brevo, Gmail...). Sin `SMTP_HOST`, los mails se escriben en el log con su enlace |
| `MAIL_FROM` | `RinkMetrics <info@madsportsapp.com>` | Remitente (tiene que ser un dominio verificado en el proveedor) |
| `APP_BASE_URL` | `http://localhost:8080` | Dirección pública de la web, para los enlaces de los mails |
| `DOLAR_URL` | DolarAPI, dólar oficial | De dónde sale el tipo de cambio para los precios en pesos |
| `DOLAR_RESPALDO` | `1500` | Pesos por dólar si DolarAPI nunca respondió |
| `SESSION_TIMEOUT` | `8h` | Sesión sin actividad |
| `REGISTRO_CLUB` | `false` | Habilita el alta de cuentas de club (las de jugador y DT están abiertas) |
| `REGISTRO_JUGADOR_PAGO` | `false` | Habilita elegir el plan Jugador mensual (pago) |
| `REGISTRO_JUGADOR_AMP` | `false` | Habilita el alta de jugadores de la AMP (por ahora, solo de la FPP) |
| `REGISTRO_DT_PAGO` | `false` | Habilita elegir el plan DT mensual (pago) |
| `RECORDARME_DIAS` | `30` | Días que queda recordado un dispositivo |
| `RECORDARME_CLAVE` | al azar | Clave de la sesión que arma "Recordarme" (no hace falta fijarla) |
| `FORWARD_HEADERS_STRATEGY` | `none` | `native` detrás de un proxy: IP real para el límite de intentos |
| `LIMITE_LOGIN_USUARIO`, `LIMITE_LOGIN_IP`, `LIMITE_REGISTRO_IP`, `LIMITE_MAILS_IP` | `5`, `20`, `5`, `10` | Límite de intentos |
| `ALERTAS_EMAIL` | vacío | Recibe las alertas del chequeo diario de SIDGAD (vacío: solo log) |
| `MONITOR_SIDGAD_CRON` | `0 0 8 * * *` | Hora del chequeo diario de SIDGAD (hora argentina; `-` lo apaga) |

**Health check:** `GET /actuator/health` (público, sin detalles) con el estado de la base y del último chequeo diario
de SIDGAD; `GET /actuator/health/liveness` para la sonda del proveedor.

La configuración propia va bajo `rinkmetrics:` en `application.yml` (clase `RinkMetricsProperties`).

**API key gratuita:** se crea en Google AI Studio (https://aistudio.google.com/apikey) con una cuenta de Google.
En el plan gratuito Google puede usar el contenido enviado para mejorar sus productos (son datos deportivos
públicos de la FPP y la AMP) y hay límites de pedidos por minuto y por día; los modelos y límites cambian seguido.

Categorías relevadas (de Infantil para arriba), en `Federacion`:

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

- `POST /api/registro` `{"perfil":"PLAYERFPP","usuario":"...","clave":"...","email":"...","clubId":22,"categoria":"PRIMERA","rama":"MASCULINO","jugadorId":5973,"suscripcion":"PLAYER_MENSUAL"}` → `201`
- `GET /api/registro/jugadores?clubId=22&categoria=PRIMERA&rama=MASCULINO&federacion=FPP`: plantel del equipo según
  sus actas (`federacion` FPP o AMP; sin ella, FPP)
- `POST /api/registro` de un DT: `"perfil":"DTFPP"` y `"suscripcion":"DT_MENSUAL"` (sin equipos)
- `GET /api/suscripciones?perfil=CLUBFPP` (o `PLAYERFPP`, `DTAMP`...): el plan mensual del perfil, con el precio en
  pesos (y el precio base en dólares, la cotización usada y su fecha), los informes por mes y, para el DT, los
  partidos por mes
- `POST /api/login` (form: `usuario`, `clave`) → `{inicio}`; `POST /api/logout`
- `GET /api/sesion`: usuario, perfiles, federación, club, jugador y la suscripción
  (`autenticado: false` sin sesión)
- `GET /api/verificar?token=...`: enlace del mail; confirma el email y lleva al ingreso.
  `POST /api/verificacion/reenviar` `{"email"}` o `{"usuario"}`
- `POST /api/clave/olvido` `{"email","producto"}` (`producto`: `REPORTS` o `METRICS`, el de la pantalla);
  `GET /api/registro/opciones` → `{club, jugadorAmp}` (qué perfiles se pueden registrar hoy); `POST /api/clave/restablecer` `{"token","clave"}`
- `POST /api/jugador/informes` `{"anio":2026,"periodo":"TEMPORADA"}` → `202 {id, estado}`; `GET /api/jugador/informes/{id}` y `/html`, `/pdf` y `/xlsx` (PLAYERFPP o PLAYERAMP)
- Métricas DT (DTFPP o DTAMP; sin `equipo`, el primer equipo del DT; lo de otro DT da 404):
  - `GET /api/dt/equipos`: los equipos con `jugados`, `ganados`, `empatados` y `perdidos` (partidos finalizados);
    `POST /api/dt/equipos` `{"nombre"}` → `201` (`409` si ya tiene los equipos del plan); `PUT /api/dt/equipos/{id}`; `DELETE /api/dt/equipos/{id}` → `204`
    (`409` si tiene partidos)
  - `GET /api/dt/jugadores`: todos los jugadores con sus `equipos`; `POST /api/dt/jugadores`
    `{"nombre","apellido","numero","arquero","equipos":[1,2]}` → `201` (id desde 900000000); `PUT /api/dt/jugadores/{id}`
    (mismo cuerpo); `DELETE /api/dt/jugadores/{id}` → `204` (baja: sus acciones quedan). `409` si el número ya es
    de otro jugador del mismo equipo.
  - `GET /api/dt/plantel?equipo=1`: los jugadores activos del equipo (`id`, `numero`, `nombre` completo, `nombres`,
    `apellido`, `arquero`, `equipos`)
  - `GET /api/dt/acciones?equipo=1`: acciones activas de todos los partidos del equipo (con `partidoId`), para las fichas
  - `POST /api/dt/partidos` `{"equipo":1,"rival":"HURACAN","fecha":"2026-10-18","local":false,"torneo":"Apertura","minutos":20}`
    → `201` (sin `fecha`, hoy; sin `minutos`, 25; `torneo` opcional); con el mismo rival y fecha devuelve el ya
    empezado; `409` si se acabó el cupo del mes; `DELETE /api/dt/partidos/{id}` → `204` lo elimina con sus acciones
  - `GET /api/dt/partidos`, `GET /api/dt/partidos/{id}` (con `reloj {tiempo, jugados, restante, corriendo}` y las
    acciones)
  - `POST /api/dt/partidos/{id}/acciones` `[{"id":"<uuid>","jugadorId":11,"tipo":"TIRO","resultado":"GOL","tiempo":1,"segundo":390}]`
    (`tipo`: `PERDIDA`, `RECUPERACION`, `TIRO`, `ATAJADA`, `GOL_RECIBIDO`, `LIBRE_FAVOR`, `LIBRE_CONTRA`,
    `PENAL_FAVOR`, `PENAL_CONTRA`, `FALTA`, `TARJETA`); `DELETE /api/dt/partidos/{id}/acciones/{accionId}` deshace
  - `POST /api/dt/partidos/{id}/reloj` `{"accion":"INICIAR"}` (`PAUSAR`, `REANUDAR`, `FIN_TIEMPO`, o `AJUSTAR` con
    `segundos`); `POST /api/dt/partidos/{id}/cerrar` y `/reabrir`
  - `GET /api/dt/partidos/{id}/informe?jugador=j11` y `/informe.pdf`: informe del partido (`jugador`: "j" + id del
    plantel, o "m" + nombre de un jugador agregado a mano; sin él, todo el equipo)
  - `GET /api/dt/informes?equipo=1&periodo=MES&valor=2026-10&jugador=` y `/api/dt/informes.pdf`: informe de los
    partidos finalizados del período (`periodo`: `MES` "2026-10", `BIMESTRE` "2026-5" = septiembre-octubre,
    `SEMESTRE` "2026-2", `CAMPEONATO` con el nombre); `400` si no hay partidos finalizados
- `GET /api/catalogo?anio=2026&federacion=AMP` — clubes con sus categorías y ramas del año en la federación (sin
  `anio`: el más reciente con datos; sin `federacion`: FPP); `available: false` y `message` si el año no tiene datos
- `GET /api/ejemplo.pdf` — informe modelo del club completo con datos ficticios ("Club Ejemplo": Primera y Juvenil
  masculinas, Infantil femenina), marcado en cada página; no consulta
  SIDGAD ni la API de Gemini. También `GET /api/ejemplo.html` (para ver en pantalla) y `GET /api/ejemplo.xlsx`.
- `GET /api/ejemplo-jugador.pdf`: informe modelo de jugador con datos ficticios ("Tomás Ejemplo", de Juniors, que
  también juega en Primera del "Club Ejemplo"). Se arma con el mismo motor que los informes reales. Enlazado desde
  RinkReports. También `GET /api/ejemplo-jugador.html` y `GET /api/ejemplo-jugador.xlsx`.
- `POST /api/informes` (CLUBFPP o CLUBAMP, solo su club y su federación) `{"clubId":74,"categoria":"INFANTIL","rama":"FEMENINO","periodo":"TEMPORADA","anio":2026}` → `202 {id, estado}`
  (`periodo`: `APERTURA`, `CLAUSURA`, `TEMPORADA` o `ULTIMOS_5`). Sin `categoria` ni `rama`
  (`{"clubId":74,"periodo":"TEMPORADA","anio":2026}`): el informe del club completo, por rama y categoría; con una
  sola de las dos, `400`
- `GET /api/informes/{id}` — estado: `PENDIENTE`, `EXTRAYENDO`, `ANALIZANDO`, `GENERANDO_PDF`, `LISTO`, `ERROR`
- `GET /api/informes/{id}/html` (el informe para ver en pantalla), `/pdf` y `/xlsx` (Excel); el estado listo trae
  `htmlUrl`, `pdfUrl` y `xlsxUrl`

## Tests

```bash
./mvnw test     # unitarios (*Test), con H2 en memoria: no necesitan Docker
./mvnw verify   # unitarios + integración (*IT) contra PostgreSQL 16 real (Testcontainers; requiere Docker) + cobertura
npm install     # una vez (Node 22 o más nuevo): Playwright para los E2E
npm test        # módulos de js/dt y contrato de la API contra la demo (node:test, sin navegador)
npm run e2e     # E2E de la demo con Playwright (celular y escritorio; usa el Chrome instalado)
npm run demo    # la demo sin levantar la app: http://localhost:8099/dt-demo.html
```

| Suite | Plugin | Base | Qué cubre |
|---|---|---|---|
| `*Test` | surefire | H2 en memoria | Parsers contra fixtures, motor de estadísticas, informes, servicios y la API completa con MockMvc |
| `*IT` | failsafe | PostgreSQL 16 (un contenedor por clase) | `MigracionesIT` (las dos carpetas de migraciones tienen las mismas versiones, se aplican todas y el esquema queda con zona horaria, cascadas y únicos) y los flujos de la API sobre la base de producción: `SecurityIT`, `AltaInformesCerradaIT`, `LimiteIntentosIT`, `DtControllerIT`, `ContratoApiDtIT` (heredan los casos de su `*Test`) |
| `src/test/js/*.test.mjs` | node:test | — | Los módulos de `js/dt` (períodos y métricas de los informes) y el contrato de la API contra la API simulada de la demo |
| `src/test/e2e/*.spec.mjs` | Playwright | — | La demo en un navegador (celular y escritorio): carga de acciones, deshacer, arquero en cancha, sin señal, fin del partido con su informe, Control panel, Partidos, Estadísticas, Informes, ABM de Equipo y equipos que cambian en otro dispositivo |

**Contrato de la API de Métricas DT** (`src/test/resources/dt/contrato-api.json`): escenarios de pedidos y respuestas
(equipos, jugadores, partido en vivo e informes, con las métricas calculadas a mano) que corren el servidor
(`ContratoApiDtTest` con H2 y `ContratoApiDtIT` con PostgreSQL) y la API simulada de la demo (`js/dt/demo-api.js`,
que arma los informes con `js/dt/informe.js`, el mismo cálculo de la pantalla sin señal). Así la demo, la pantalla
sin señal y el servidor no se separan: un cambio en uno sin el otro rompe el CI.

La cobertura de JaCoCo suma las dos suites y queda en `target/site/jacoco/index.html`.

El CI de GitHub Actions (`.github/workflows/ci.yml`) corre en cada PR y en cada push a `main` dos jobs: **Backend**
(`./mvnw verify`: publica el resumen de cobertura en la corrida, el reporte como artefacto y, si algo falla, los
reportes de surefire y failsafe) y **Pantallas** (`npm test` y los E2E con el Chromium de Playwright; si fallan,
publica el reporte con trazas y capturas). Dependabot (`.github/dependabot.yml`) abre PRs semanales con las
dependencias de Maven, de npm y las acciones de GitHub, que valida el mismo CI.

Los parsers y el motor se prueban contra HTML real guardado en `src/test/resources/fixtures` (octubre de 2026:
actas, calendarios, posiciones, la ficha de temporada de un jugador y el catálogo 2026 con la FPP y la AMP). Si SIDGAD
cambia el markup, actualizar los fixtures y ver qué test falla. La seguridad, el registro y los planes se prueban con
MockMvc y H2 en memoria (el dólar de los tests es fijo: $ 1.500).

El proyecto usa **Maven 3.9.11** por el wrapper: IntelliJ 2025.3 no arranca su Maven con 3.10.0
(`No implementation for ArtifactHandlerManager was bound`). Con IntelliJ, usar "Maven wrapper" en la configuración
de Maven y recargar el proyecto.

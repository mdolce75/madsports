// Demo de RinkMetrics (/coach-demo.html, la misma página que /coach.html): un equipo de ejemplo con su plantel, partidos ya
// jugados, un partido en juego y dos programados. Los pedidos a /api/ no salen del navegador: los contesta demo-api.js
// como lo haría el servidor. Nada se guarda: al recargar, la demo vuelve a empezar.
import {createApi} from "./demo-api.js";

const DUR = 25 * 60;
const iso = d => d.toISOString().slice(0, 10);
const today = iso(new Date());
const day = (months, d) => { const x = new Date(); x.setMonth(x.getMonth() - months); x.setDate(d); return iso(x); };
const inDays = n => { const x = new Date(); x.setDate(x.getDate() + n); return iso(x); };

const SESSION = {
    authenticated: true, username: "demo", name: "DT", lastName: "Demo", email: null, profiles: ["COACHFPP"],
    club: {id: 0, name: "CLUB DEMO", code: "DEMO"}, player: null, home: "/coach-demo.html",
    subscription: {code: "COACH_MONTHLY", name: "DT mensual", maxTeams: 3, matchesPerMonth: 25},
    reportsRequested: 0, federation: "FPP",
};
const SQUAD = [
    [101, "1", "Martín", "Acosta", true], [102, "12", "Lucas", "Benítez", true], [103, "3", "Tomás", "Ferreyra", false],
    [104, "5", "Joaquín", "Ríos", false], [105, "7", "Facundo", "Medina", false], [106, "8", "Nicolás", "Sosa", false],
    [107, "9", "Agustín", "Paz", false], [108, "10", "Santiago", "Herrera", false], [109, "11", "Bruno", "Castro", false],
    [110, "14", "Mateo", "Giménez", false],
].map(([id, number, names, lastName, goalkeeper]) => ({id, number, names, lastName, goalkeeper, teams: [1]}));
const nameOf = id => { const j = SQUAD.find(x => x.id === id); return j ? j.names + " " + j.lastName : ""; };

let seq = 0;
const action = (playerId, kind, outcome, period, atSecond) => ({
    id: "00000000-0000-4000-8000-" + String(++seq).padStart(12, "0"), playerId, playerName: nameOf(playerId),
    kind, outcome, period, atSecond});

// Historia de la demo: partidos ya jugados en los últimos meses (con acciones generadas siempre iguales).
let seed = 7;
const randomize = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
const choose = list => list[Math.floor(randomize() * list.length)];
const FIELD = SQUAD.filter(j => !j.goalkeeper).map(j => j.id);
const TYPES = [["TURNOVER", "OWN_HALF"], ["TURNOVER", "RIVAL_HALF"], ["RECOVERY", "OWN_HALF"], ["RECOVERY", "RIVAL_HALF"],
    ["RECOVERY", "RIVAL_HALF"], ["SHOT", "GOAL"], ["SHOT", "NO_GOAL"], ["SHOT", "NO_GOAL"], ["FOUL", "COMMITTED"],
    ["FOUL", "RECEIVED"], ["FREE_HIT_FOR", "NO_GOAL"], ["FREE_HIT_FOR", "GOAL"], ["PENALTY_FOR", "GOAL"],
    ["PENALTY_FOR", "NO_GOAL"], ["CARD", "YELLOW"]];

function played(id, opponent, date, home, tournament) {
    const actions = [];
    for (let i = 0; i < 26; i++) {
        const [kind, res] = choose(TYPES);
        // Los delanteros (7, 9, 10) tiran más; el resto recupera más.
        const j = ["SHOT", "PENALTY_FOR", "FREE_HIT_FOR"].includes(kind) ? choose([105, 107, 108, 108, 109]) : choose(FIELD);
        actions.push(action(j, kind, res, 1 + (i % 2), Math.floor(randomize() * DUR)));
    }
    for (let i = 0; i < 4; i++) actions.push(action(choose([101, 101, 102]), choose(["SAVE", "SAVE", "GOAL_CONCEDED"]), null, 1 + (i % 2), Math.floor(randomize() * DUR)));
    return {id, teamId: 1, tournament, opponent, date, home, periodMinutes: 25, status: "CLOSED", period: 2, seconds: DUR,
        closedAt: Date.now(), actions};
}

const MATCHES = [
    // El partido de ejemplo ya empezado: 1T, 12' jugados, con algunas acciones cargadas.
    {id: 1, teamId: 1, tournament: "Clausura", opponent: "Deportivo Oeste", date: today, home: true, periodMinutes: 25,
        status: "IN_PLAY", period: 1, seconds: 12 * 60 + 18, from: Date.now(), actions: [
            action(104, "RECOVERY", "RIVAL_HALF", 1, 95), action(104, "SHOT", "GOAL", 1, 101),
            action(107, "TURNOVER", "OWN_HALF", 1, 212), action(101, "SAVE", null, 1, 215),
            action(108, "FOUL", "COMMITTED", 1, 288), action(101, "FREE_HIT_AGAINST", "SAVED", 1, 290),
            action(105, "SHOT", "NO_GOAL", 1, 344), action(106, "RECOVERY", "OWN_HALF", 1, 402),
            action(109, "CARD", "BLUE", 1, 455), action(107, "SHOT", "GOAL", 1, 610),
            action(101, "GOAL_CONCEDED", null, 1, 668),
        ]},
    played(2, "Atlético Norte", day(1, 20), false, "Clausura"), played(3, "Unión Patín", day(1, 6), true, "Clausura"),
    played(4, "Sporting Central", day(2, 24), true, "Clausura"), played(5, "Club Social Sur", day(2, 9), false, "Apertura"),
    played(6, "Deportivo Oeste", day(3, 15), false, "Apertura"), played(7, "Atlético Norte", day(4, 18), true, "Apertura"),
    {id: 8, teamId: 1, tournament: "Clausura", opponent: "Club Social Sur", date: inDays(3), home: true, periodMinutes: 25,
        status: "PENDING"},
    {id: 9, teamId: 1, tournament: "Clausura", opponent: "Unión Patín", date: inDays(10), home: false, periodMinutes: 25,
        status: "PENDING"},
];

// El club de la demo en SIDGAD: sus categorías y el plantel de cada una (los jugadores de sus actas), para armar un
// equipo desde una categoría. En Juvenil juega también Santiago Herrera, que ya está en Primera.
const sidgad = (id, number, names, lastName, goalkeeper = false) =>
    ({id, number, name: names + " " + lastName, names, lastName, goalkeeper});
const CLUB = {
    categoryList: [
        {key: "PRIMERA", branch: "MASCULINO", name: "Primera Masculino"},
        {key: "JUVENIL", branch: "MASCULINO", name: "Juvenil Masculino"},
        {key: "INFANTIL", branch: "FEMENINO", name: "Infantil Femenino"},
    ],
    squads: {
        "PRIMERA|MASCULINO": SQUAD.map(j => sidgad(j.id, j.number, j.names, j.lastName, j.goalkeeper)),
        "JUVENIL|MASCULINO": [
            sidgad(3001, "1", "Gonzalo", "Vera", true), sidgad(3002, "2", "Ramiro", "Ortiz"),
            sidgad(3003, "4", "Iván", "Luna"), sidgad(3004, "6", "Thiago", "Romero"),
            sidgad(3005, "8", "Lautaro", "Molina"), sidgad(3006, "11", "Benjamín", "Suárez"),
            sidgad(108, "10", "Santiago", "Herrera"), sidgad(3007, "13", "Valentín", "Acuña"),
        ],
        "INFANTIL|FEMENINO": [
            sidgad(4001, "1", "Sofía", "Méndez", true), sidgad(4002, "3", "Martina", "Rojas"),
            sidgad(4003, "5", "Camila", "Torres"), sidgad(4004, "7", "Julieta", "Ibáñez"),
            sidgad(4005, "9", "Lucía", "Farías"),
        ],
    },
};

/** Instala la demo: los pedidos a /api/ los contesta la API simulada; el resto (fuentes, estilos) va a la red. */
export function instalar() {
    const api = createApi({session: SESSION, teams: [{id: 1, name: "Primera Masculino", sidgadCategory: "PRIMERA",
        sidgadBranch: "MASCULINO"}], players: SQUAD, matches: MATCHES, club: CLUB});
    const red = window.fetch.bind(window);
    window.fetch = async (input, init = {}) => {
        const url = typeof input === "string" ? input : input.url;
        const u = new URL(url, location.origin);
        if (!u.pathname.startsWith("/api/")) return red(input, init);
        if (u.pathname === "/api/logout") { location.replace("/rinkmetrics"); return new Response("{}"); }
        await new Promise(r => setTimeout(r, 60));   // como una respuesta real, un instante después
        return api((init.method || "GET").toUpperCase(), u.pathname + u.search, init.body ? JSON.parse(init.body) : null);
    };

    // La demo arranca en el Control panel, con el menú completo. Con ?tab= (capturas) abre directamente el partido en
    // esa pestaña; con ?informe=TIPO, ese tipo de informe.
    const q = new URLSearchParams(location.search);
    if (!location.hash) history.replaceState(null, "", location.pathname + location.search + (q.get("tab") ? "#match=1" : "#dashboard"));
    const tab = q.get("tab"), report = q.get("report");
    if (report) setTimeout(() => document.querySelector(`#i-tipo [data-kind='${report}']`)?.click(), 700);
    if (tab) setTimeout(() => {
        document.querySelector("[aria-label^='9 '], [aria-label^='10 ']")?.click();
        $("t-" + tab)?.click();
    }, 700);
    // Un jugador ya elegido en el partido de ejemplo, para que se vea la selección.
    setTimeout(() => {
        if (location.hash === "#match=1" && !document.querySelector(".coach-player[aria-pressed='true']")) {
            document.querySelector("[aria-label^='10 ']")?.click();
        }
    }, 600);
}

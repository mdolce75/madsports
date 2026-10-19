import {defineConfig, devices} from "@playwright/test";

/**
 * E2E de Métricas DT sobre la demo: la pantalla actual (/dt-demo.html) y la versión en React (/dt-app/?demo), con la
 * API simulada en el navegador (js/dt/demo-api.js, verificada contra el contrato del servidor). No necesita backend:
 * e2e/servidor.mjs sirve los archivos estáticos y el build de React (antes: npm run build).
 *
 * E2E_BROWSER_CHANNEL: navegador. Por defecto el Chrome instalado (no hace falta `npx playwright install`); `chromium`
 * usa el que descarga Playwright (en el CI).
 */
const channel = process.env.E2E_BROWSER_CHANNEL ?? "chrome";
const PUERTO = 8099;

export default defineConfig({
    testDir: "e2e",
    testMatch: "**/*.spec.mjs",
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: 0,
    timeout: 30_000,
    expect: {timeout: 5_000},
    reporter: [["list"], ["html", {open: "never", outputFolder: "playwright-report"}]],
    outputDir: "test-results",
    use: {
        baseURL: `http://localhost:${PUERTO}`,
        channel: channel === "chromium" ? undefined : channel,
        locale: "es-AR",
        timezoneId: "America/Argentina/Buenos_Aires",
        trace: "retain-on-failure",
        screenshot: "only-on-failure",
    },
    // El DT carga en la cancha con el celular; los informes y el ABM también se usan en la computadora.
    projects: [
        {name: "celular", use: {...devices["Pixel 7"], channel: channel === "chromium" ? undefined : channel}},
        {name: "escritorio", use: {viewport: {width: 1280, height: 800}}},
    ],
    webServer: {
        command: "node e2e/servidor.mjs",
        url: `http://localhost:${PUERTO}/dt-demo.html`,
        reuseExistingServer: !process.env.CI,
    },
});

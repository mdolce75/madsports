/// <reference types="vitest/config" />
import {defineConfig} from "vite";
import react from "@vitejs/plugin-react";
import {fileURLToPath} from "node:url";

// Métricas DT en React. Se sirve en /dt-app/ (el build de Maven la deja en target/classes/static/dt-app). Usa los
// estilos y los módulos de dominio que comparte con las páginas estáticas (src/main/resources/static): mientras dure
// la migración hay un solo cálculo de métricas, una sola cola sin señal y una sola API de la demo.
// En desarrollo, /api, /css e /img se piden a la app de Spring (por defecto en el 8080).
const backend = process.env.VITE_BACKEND ?? "http://localhost:8080";

export default defineConfig({
    base: "/dt-app/",
    plugins: [react()],
    resolve: {
        // Módulos de dominio de Métricas DT (acciones, informes, fichas, cola, API de la demo), compartidos con dt.html.
        alias: {"@dominio": fileURLToPath(new URL("../src/main/resources/static/js/dt", import.meta.url))},
    },
    server: {
        port: 5173,
        fs: {allow: [".."]},
        proxy: {
            "/api": {target: backend, changeOrigin: true},
            "/css": {target: backend, changeOrigin: true},
            "/img": {target: backend, changeOrigin: true},
        },
    },
    build: {
        target: "es2022",
        sourcemap: false,
    },
    test: {
        environment: "jsdom",
        globals: true,
        setupFiles: ["./src/test/setup.ts"],
        include: ["src/**/*.test.{ts,tsx}", "tests/**/*.test.mjs"],
        css: false,
        restoreMocks: true,
        coverage: {
            provider: "v8",
            reporter: ["text", "html"],
            include: ["src/**/*.{ts,tsx}"],
            exclude: ["src/test/**", "src/main.tsx", "src/**/*.d.ts"],
        },
    },
});

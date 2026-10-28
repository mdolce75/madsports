// Textos que nombran a la AMP ([data-amp]): ocultos mientras la AMP no esté habilitada (madsports.signup.amp, el
// mismo interruptor que el registro), así la web la nombra recién cuando se puede usar.
(async () => {
    const texts = document.querySelectorAll("[data-amp]");
    if (!texts.length) return;
    const options = await fetch("/api/signup/options").then(r => r.ok ? r.json() : {}).catch(() => ({}));
    texts.forEach(t => { t.hidden = !options.amp; });
})();

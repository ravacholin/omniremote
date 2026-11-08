/* Runs in Chrome's isolated AppleScript world. No site-private JavaScript APIs. */
function macWebMedia(request) {
  function error(code, message) { return {present: false, error: {code: code, message: message}}; }
  function classify() {
    var u = new URL(location.href), h = u.hostname, p = u.pathname;
    if (u.protocol !== 'https:') return null;
    if ((h === 'www.youtube.com' || h === 'youtube.com' || h === 'm.youtube.com') &&
        ((p === '/watch' && u.searchParams.has('v')) || /^\/(shorts|live)\/[^/]+/.test(p))) return 'YOUTUBE';
    if (h === 'music.youtube.com' && /^\/(watch|browse|playlist|search)?(\/.*)?$/.test(p)) return 'YOUTUBE_MUSIC';
    if (h === 'open.spotify.com' && (p === '/' || /^\/(intl-[a-z-]+\/)?(track|album|playlist|artist|collection|episode|show|search|genre|browse)(\/|$)/.test(p))) return 'SPOTIFY_WEB';
    return null;
  }
  if (classify() !== request.service) return error('SOURCE_GONE', 'La pestaña cambió de servicio o no tiene una ruta compatible.');
  function find(selector) { return document.querySelector(selector); }
  function enabled(button) {
    if (button && typeof getComputedStyle === 'function') {
      var style = getComputedStyle(button);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
    }
    return button && !button.disabled && button.getAttribute('aria-disabled') !== 'true' &&
      button.getAttribute('hidden') === null && button.getAttribute('aria-hidden') !== 'true';
  }
  var spotify = request.service === 'SPOTIFY_WEB';
  var music = request.service === 'YOUTUBE_MUSIC';
  var media = spotify ? null : find('video.html5-main-video');
  if (!media) {
    var all = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
    media = all.filter(function (m) { return m.readyState > 0 && !m.ended; }).sort(function (a, b) {
      return Number(a.paused) - Number(b.paused);
    })[0] || null;
  }
  if (media && media.readyState === 0) media = null;
  var play = spotify ? find('[data-testid="control-button-playpause"]') : null;
  if (!media && !(spotify && enabled(play))) return {present: false};
  var playback = media ? (media.paused || media.ended ? 'PAUSED' : 'PLAYING') : 'UNKNOWN';
  if (!media && play) {
    var label = (play.getAttribute('aria-label') || '').toLowerCase();
    if (/^(pause|pausa|pausar|anhalten|mettre en pause)$/.test(label)) playback = 'PLAYING';
    if (/^(play|reproducir|reproduzir|lecture|wiedergeben)$/.test(label)) playback = 'PAUSED';
  }
  var next = find(spotify ? '[data-testid="control-button-skip-forward"]' : music ? 'ytmusic-player-bar .next-button' : '.ytp-next-button');
  var previous = find(spotify ? '[data-testid="control-button-skip-back"]' : music ? 'ytmusic-player-bar .previous-button' : '.ytp-prev-button');
  var advertising = !!find('.ad-showing') || !!find('[data-testid="ad-link"]');
  var capabilities = ['PLAY_PAUSE'];
  if (media || playback !== 'UNKNOWN') capabilities = capabilities.concat(['PLAY', 'PAUSE']);
  if (!advertising && enabled(next)) capabilities.push('NEXT');
  if (!advertising && enabled(previous)) capabilities.push('PREVIOUS');
  // An isolated-world property identifies the document without modifying the page DOM.
  if (!document.documentElement.__omniRemoteDocument) document.documentElement.__omniRemoteDocument = String(Date.now()) + ':' + String(Math.random());
  var track = spotify ? find('[data-testid="now-playing-widget"] a[href*="/track/"], [data-testid="now-playing-widget"] a[href*="/episode/"]') : null;
  var token = JSON.stringify([document.documentElement.__omniRemoteDocument, location.href,
    media ? (media.currentSrc || media.src || '') : '', track ? track.getAttribute('href') : '']);
  if (request.op === 'action') {
    if (!request.expectedToken || request.expectedToken !== token) return error('STALE_SOURCE', 'El contenido cambió. Actualizá la fuente y volvé a pulsar.');
    if (capabilities.indexOf(request.action) < 0) return error('UNSUPPORTED', 'Esta acción no está disponible en el reproductor.');
    try {
      if (request.action === 'NEXT') next.click();
      else if (request.action === 'PREVIOUS') previous.click();
      else if (media) {
        var shouldPlay = request.action === 'PLAY' || (request.action === 'PLAY_PAUSE' && (media.paused || media.ended));
        if (shouldPlay) {
          var pending = media.play();
          // play() can reject asynchronously (autoplay). A later inspect reports actual state.
          if (pending && pending.catch) pending.catch(function () {});
        } else media.pause();
      } else if (request.action === 'PLAY_PAUSE' || (request.action === 'PLAY' && playback === 'PAUSED') ||
                 (request.action === 'PAUSE' && playback === 'PLAYING')) play.click();
    } catch (e) { return error('PLAYER_BLOCKED', 'El reproductor rechazó la orden. Iniciá la reproducción una vez en Chrome.'); }
  }
  return {present: true, title: String(document.title || '').slice(0, 250), playback: playback,
    capabilities: capabilities, token: token, verified: true};
}

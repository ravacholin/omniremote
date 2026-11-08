/* JXA host. Runs in the logged-in user's SSH session, without activating apps. */
ObjC.import('Cocoa');
function issue(code, message) { return {code:code, message:message}; }
function processId(bundle) {
  var apps = $.NSWorkspace.sharedWorkspace.runningApplications;
  for (var i=0; i<Number(apps.count); i++) {
    var app = apps.objectAtIndex(i);
    if (ObjC.unwrap(app.bundleIdentifier) === bundle) return String(Number(app.processIdentifier));
  }
  return null;
}
function failure(e, chrome) {
  var text = String(e);
  if (/JavaScript.*(off|disabled|not allowed)|Allow JavaScript|JavaScript.*Apple Events/i.test(text))
    return issue('CHROME_JAVASCRIPT', 'En Chrome activá View > Developer > Allow JavaScript from Apple Events.');
  if (/-1743|not authorized|not permitted/i.test(text))
    return issue('AUTOMATION_DENIED', 'En la Mac permití Automatización para sshd-keygen-wrapper y ' + (chrome ? 'Google Chrome.' : 'el reproductor.'));
  return issue('PLAYER_ERROR', chrome ? 'No se pudo consultar la pestaña. Revisá Automatización y JavaScript por Apple Events en Chrome.' : 'No se pudo consultar el reproductor. Revisá Automatización en la Mac.');
}
function classify(url) {
  // JXA does not provide the browser URL class. Parse exact HTTPS hosts before returning any tab.
  var m = /^https:\/\/([^/?#]+)([^?#]*)(\?[^#]*)?/.exec(url);
  if (!m) return null;
  var h=m[1], p=m[2] || '/', q=m[3] || '';
  if ((h==='www.youtube.com'||h==='youtube.com'||h==='m.youtube.com') &&
      ((p==='/watch' && /[?&]v=[^&]+/.test(q)) || /^\/(shorts|live)\/[^/]+\/?$/.test(p))) return 'YOUTUBE';
  if (h==='music.youtube.com' && /^\/(watch|browse|playlist|search)?(\/.*)?$/.test(p)) return 'YOUTUBE_MUSIC';
  if (h==='open.spotify.com' && (p==='/' || /^\/(intl-[a-z-]+\/)?(track|album|playlist|artist|collection|episode|show|search|genre|browse)(\/.*)?$/.test(p))) return 'SPOTIFY_WEB';
  return null;
}
function chromeTabs(chrome, visit) {
  var windows=chrome.windows();
  for (var w=0; w<windows.length; w++) {
    if (windows[w].mode() !== 'normal') continue;
    var tabs=windows[w].tabs();
    for (var t=0;t<tabs.length;t++) if (visit(tabs[t], String(windows[w].id())) === false) return;
  }
}
function inspectNative(source, action) {
  var bundle = source.service === 'SPOTIFY' ? 'com.spotify.client' : 'com.apple.Music';
  if (processId(bundle) !== source.processId) return {error:issue('SOURCE_GONE','La app elegida ya no está abierta.')};
  var app=Application(bundle);
  function snapshot() {
    var track='', token='';
    try { track=String(app.currentTrack.name()); } catch(e) {}
    try { token=String(app.currentTrack.id()); } catch(e) { token=track; }
    var state=String(app.playerState());
    return Object.assign({}, source, {title:track.slice(0,250),token:token,verified:true,issue:null,
      playback:state==='playing'?'PLAYING':state==='paused'||state==='stopped'?'PAUSED':'UNKNOWN',
      capabilities:['PLAY','PAUSE','PLAY_PAUSE','NEXT','PREVIOUS']});
  }
  var before=snapshot();
  if (action) {
    if (source.token !== before.token) return {source:before,error:issue('STALE_SOURCE','Cambió la pista. Volvé a pulsar sobre la fuente actualizada.')};
    if (action==='PLAY') app.play();
    else if (action==='PAUSE') app.pause();
    else if (action==='PLAY_PAUSE') app.playpause();
    else if (action==='NEXT') app.nextTrack();
    else if (action==='PREVIOUS') app.previousTrack();
    else return {error:issue('UNSUPPORTED','Acción no disponible.')};
  }
  return {source:snapshot()};
}
function run() {
  try {
    var chromePid=processId('com.google.Chrome');
    if (REQUEST.op==='discover') {
      var sources=[], issues=[];
      [['SPOTIFY','com.spotify.client'],['MUSIC','com.apple.Music']].forEach(function (entry) {
        var pid=processId(entry[1]);
        if (pid) sources.push({id:'app:'+entry[1]+':'+pid, service:entry[0], processId:pid});
      });
      if (chromePid) {
        try {
          chromeTabs(Application('com.google.Chrome'),function(tab,windowId) {
            if (sources.length>=40) return false;
            var url=String(tab.url()), service=classify(url);
            if (service) sources.push({id:'chrome:'+chromePid+':'+String(tab.id())+':'+service, service:service,
              processId:chromePid,tabId:String(tab.id()),windowId:windowId,title:String(tab.title()).slice(0,250),url:url});
          });
        } catch(e) { issues.push(failure(e,true)); }
      }
      return JSON.stringify({sources:sources,issues:issues,chromeProcessId:chromePid});
    }
    var source=REQUEST.source;
    if (!source) return JSON.stringify({error:issue('SOURCE_GONE','No hay fuente elegida.')});
    if (source.service==='SPOTIFY'||source.service==='MUSIC') return JSON.stringify(inspectNative(source,REQUEST.op==='action'?REQUEST.action:null));
    if (source.processId!==chromePid) return JSON.stringify({error:issue(chromePid ? 'CHROME_RESTARTED' : 'SOURCE_GONE','Chrome se cerró o reinició.')});
    var found=null;
    chromeTabs(Application('com.google.Chrome'),function(tab,windowId) {
      if (String(tab.id())===source.tabId) {found={tab:tab,windowId:windowId};return false;}
    });
    if (!found || classify(String(found.tab.url()))!==source.service) return JSON.stringify({error:issue('SOURCE_GONE','La pestaña se cerró o cambió de servicio.')});
    var request={op:REQUEST.op,service:source.service,action:REQUEST.action,expectedToken:source.token};
    var raw=found.tab.execute({javascript:BROWSER_SOURCE+'\nJSON.stringify(macWebMedia('+JSON.stringify(request)+'))'});
    var result=JSON.parse(String(raw));
    if (result.error) return JSON.stringify({error:result.error});
    if (!result.present) return JSON.stringify({error:issue('NO_MEDIA','No hay un reproductor cargado en esta pestaña.')});
    var merged=Object.assign({},source,result,{url:String(found.tab.url()),windowId:found.windowId,issue:null});
    delete merged.present;
    return JSON.stringify({source:merged});
  } catch(e) { return JSON.stringify({error:failure(e, REQUEST.source && REQUEST.source.service!=='SPOTIFY' && REQUEST.source.service!=='MUSIC')}); }
}

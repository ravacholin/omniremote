const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const browserPath = path.join(__dirname, '../app/src/main/resources/mac_media_browser.js');
function fixture(url = 'https://www.youtube.com/watch?v=one') {
  const video = {paused:true, ended:false, readyState:4, currentSrc:'blob:one', pause(){this.paused=true;}, play(){this.paused=false;return Promise.resolve();}};
  const selectors = {'video.html5-main-video':video};
  const document = {documentElement:{}, title:'same title', querySelector:q=>selectors[q] || null, querySelectorAll:q=>q==='video, audio'?[video]:[]};
  const context = {document,location:new URL(url),URL,Math,Date,JSON};
  return {video, selectors, context, run(request) {return vm.runInNewContext(fs.readFileSync(browserPath,'utf8')+'\nmacWebMedia(request)', {...context, request});}};
}
test('paused YouTube is discoverable and play pause is directed to the media',()=>{
  const f=fixture(); const seen=f.run({service:'YOUTUBE',op:'inspect'});
  assert.equal(seen.present,true); assert.equal(seen.playback,'PAUSED'); assert.ok(seen.capabilities.includes('PLAY_PAUSE'));
  f.run({service:'YOUTUBE',op:'action',action:'PLAY',expectedToken:seen.token}); assert.equal(f.video.paused,false);
  f.run({service:'YOUTUBE',op:'action',action:'PLAY',expectedToken:f.run({service:'YOUTUBE',op:'inspect'}).token}); assert.equal(f.video.paused,false);
});
test('missing or disabled next button cannot advance or navigate browser history',()=>{
  const f=fixture(); f.selectors['.ytp-next-button']={disabled:true,getAttribute:()=>null,click(){throw Error('must not click');}};
  const seen=f.run({service:'YOUTUBE',op:'inspect'}); assert.ok(!seen.capabilities.includes('NEXT'));
  assert.equal(f.run({service:'YOUTUBE',op:'action',action:'NEXT',expectedToken:seen.token}).error.code,'UNSUPPORTED');
});
test('navigation and media replacement reject an old command',()=>{
  const f=fixture(); const seen=f.run({service:'YOUTUBE',op:'inspect'});
  f.context.location=new URL('https://www.youtube.com/watch?v=two');
  assert.equal(f.run({service:'YOUTUBE',op:'action',action:'PLAY',expectedToken:seen.token}).error.code,'STALE_SOURCE');
  assert.equal(f.video.paused,true);
});
test('unrelated pages and home pages never expose a usable YouTube player',()=>{
  for(const url of ['https://www.youtube.com/','https://youtube.com.evil.test/watch?v=a','https://www.youtube.com/results?search_query=x']) {
    assert.equal(fixture(url).run({service:'YOUTUBE',op:'inspect'}).present,false);
  }
});
test('YouTube Music and Spotify DOM controls work without private site globals',()=>{
  const y=fixture('https://music.youtube.com/watch?v=a');
  assert.equal(y.run({service:'YOUTUBE_MUSIC',op:'inspect'}).present,true);
  const s=fixture('https://open.spotify.com/track/one'); s.context.document.querySelectorAll=()=>[];
  let clicks=0; s.selectors['[data-testid="control-button-playpause"]']={disabled:false,getAttribute:n=>n==='aria-label'?'Reproducir':null,click(){clicks++;}};
  const seen=s.run({service:'SPOTIFY_WEB',op:'inspect'}); assert.equal(seen.playback,'PAUSED');
  s.run({service:'SPOTIFY_WEB',op:'action',action:'PLAY',expectedToken:seen.token}); assert.equal(clicks,1);
});
test('advertisements disable skip controls',()=>{
  const f=fixture(); f.selectors['.ad-showing']={}; f.selectors['.ytp-next-button']={getAttribute:()=>null,click(){throw Error('skip ad');}};
  assert.ok(!f.run({service:'YOUTUBE',op:'inspect'}).capabilities.includes('NEXT'));
});
function hostFixture() {
  const f=fixture(), activated=[];
  let pid='42';
  const tabs=[{id:()=> '11',url:()=> 'https://www.youtube.com/watch?v=one',title:()=> 'same title',execute:({javascript})=>vm.runInNewContext(javascript,f.context)},
              {id:()=> '12',url:()=> 'https://www.youtube.com/watch?v=one',title:()=> 'same title',execute:({javascript})=>vm.runInNewContext(javascript,f.context)},
              {id:()=> '13',url:()=> 'https://mail.google.com/mail',title:()=> {throw Error('must not read unrelated title');}}];
  const windows=[{id:()=> '1',mode:()=> 'incognito',tabs:()=>{throw Error('must not inspect incognito');}},
                 {id:()=> '2',mode:()=> 'normal',tabs:()=>tabs}];
  const app={windows:()=>windows,activate:()=>activated.push('chrome')};
  const ObjC={import(){},unwrap:x=>x};
  return {tabs,windows,activated,setPid(p){pid=p;},run(request){
    const processes=[{bundleIdentifier:'com.google.Chrome',get processIdentifier(){return Number(pid);}}];
    const runningApplications={count:processes.length,objectAtIndex:i=>processes[i]};
    return JSON.parse(vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/src/main/resources/mac_media_host.js'),'utf8')+'\nrun()',{
      REQUEST:request, BROWSER_SOURCE:fs.readFileSync(browserPath,'utf8'), ObjC,
      $:{NSWorkspace:{sharedWorkspace:{runningApplications}}},Application:()=>app
    }));
  }};
}
test('host discovers separate equal-titled tabs and filters unrelated or incognito before returning metadata',()=>{
  const f=hostFixture(), r=f.run({op:'discover'});
  assert.deepEqual(r.sources.map(s=>s.id),['chrome:42:11:YOUTUBE','chrome:42:12:YOUTUBE']);
  assert.equal(r.chromeProcessId,'42'); assert.deepEqual(f.activated,[]);
});
test('reveal selects the exact equal-titled tab in its current restored window without web JavaScript',()=>{
  const f=hostFixture(), source=f.run({op:'discover'}).sources[1];
  const moved=f.tabs.splice(1,1)[0];
  moved.execute=()=>{throw Error('reveal must not execute player JavaScript');};
  const other={id:()=> '14',url:()=> 'https://www.youtube.com/watch?v=other'};
  const window={id:()=> '9',mode:()=> 'normal',tabs:()=>[other,moved],minimized:true,index:3,activeTabIndex:1};
  f.windows.push(window);
  const result=f.run({op:'reveal',source});
  assert.equal(result.error,undefined);
  assert.equal(result.revealed,true);
  assert.equal(window.activeTabIndex,2);
  assert.equal(window.minimized,false);
  assert.equal(window.index,1);
  assert.deepEqual(f.activated,['chrome']);
});
test('reveal never activates a closed tab, changed service or restarted browser',()=>{
  for (const change of [
    f=>f.tabs.splice(0,1),
    f=>{f.tabs[0].url=()=> 'https://open.spotify.com/track/a';},
    f=>f.setPid('77'),
    f=>{f.windows[1].mode=()=> 'incognito';}
  ]) {
    const f=hostFixture(), source=f.run({op:'discover'}).sources[0];
    change(f);
    assert.ok(f.run({op:'reveal',source}).error);
    assert.deepEqual(f.activated,[]);
  }
});
test('reveal reports Automation failure without running playback commands',()=>{
  const f=hostFixture(), source=f.run({op:'discover'}).sources[0];
  Object.defineProperty(f.windows[1],'activeTabIndex',{set(){throw Error('not authorized -1743');}});
  const result=f.run({op:'reveal',source});
  assert.equal(result.error.code,'AUTOMATION_DENIED');
  assert.deepEqual(f.activated,[]);
});
function nativeHostFixture(service) {
  const bundle=service==='SPOTIFY'?'com.spotify.client':'com.apple.Music';
  let pid=42;
  const activated=[];
  const app={activate(){activated.push(bundle);}}; // Any player inspection/action would fail.
  return {activated,setPid(p){pid=p;},run(){
    return JSON.parse(vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../app/src/main/resources/mac_media_host.js'),'utf8')+'\nrun()',{
      REQUEST:{op:'reveal',source:{id:'app:'+bundle+':42',service,processId:'42'}}, BROWSER_SOURCE:'',
      ObjC:{import(){},unwrap:x=>x},
      $:{NSWorkspace:{sharedWorkspace:{runningApplications:{count:1,objectAtIndex:()=>({bundleIdentifier:bundle,processIdentifier:pid})}}}},
      Application:b=>{assert.equal(b,bundle);return app;}
    }));
  }};
}
test('reveal activates only the selected running native app without inspecting its track',()=>{
  for (const service of ['SPOTIFY','MUSIC']) {
    const f=nativeHostFixture(service);
    assert.equal(f.run().revealed,true);
    assert.equal(f.activated.length,1);
    f.setPid(99);
    assert.equal(f.run().error.code,'SOURCE_GONE');
    assert.equal(f.activated.length,1);
  }
});
test('host targets tab ID after reorder and move without activating Chrome',()=>{
  const f=hostFixture(), source=f.run({op:'discover'}).sources[1];
  const tab=f.tabs.splice(1,1)[0]; f.windows.push({id:()=> '9',mode:()=> 'normal',tabs:()=>[tab]});
  const read=f.run({op:'inspect',source}); assert.equal(read.source.tabId,'12');assert.equal(read.source.windowId,'9');
  const result=f.run({op:'action',source:read.source,action:'PLAY'});
  assert.equal(result.error,undefined); assert.deepEqual(f.activated,[]);
});
test('host rejects tabs from a restarted Chrome and handles permission errors as data',()=>{
  const f=hostFixture(), source=f.run({op:'discover'}).sources[0]; f.setPid('77');
  assert.equal(f.run({op:'action',source,action:'PLAY'}).error.code,'CHROME_RESTARTED');
  const s=f.run({op:'discover'}).sources[0]; f.tabs[0].execute=()=>{throw Error('Executing JavaScript through AppleScript is turned off.');};
  assert.equal(f.run({op:'inspect',source:s}).error.code,'CHROME_JAVASCRIPT');
});
test('a hidden skip control is not advertised as usable',()=>{
  const f=fixture(); const hidden={disabled:false,getAttribute:()=>null,click(){throw Error('hidden control');}};
  f.selectors['.ytp-next-button']=hidden;
  f.context.getComputedStyle=()=>({display:'none',visibility:'visible'});
  assert.ok(!f.run({service:'YOUTUBE',op:'inspect'}).capabilities.includes('NEXT'));
});
test('YouTube Music unrelated routes do not become candidates',()=>{
  assert.equal(fixture('https://music.youtube.com/settings').run({service:'YOUTUBE_MUSIC',op:'inspect'}).present,false);
});
test('host identifies a restarted Chrome distinctly from a closed tab',()=>{
  const f=hostFixture(), source=f.run({op:'discover'}).sources[0]; f.setPid('77');
  assert.equal(f.run({op:'inspect',source}).error.code,'CHROME_RESTARTED');
});
test('navigating a tab to another service yields a new selectable source identity',()=>{
  const f=hostFixture(), old=f.run({op:'discover'}).sources[0];
  f.tabs[0].url=()=> 'https://open.spotify.com/track/a';
  const changed=f.run({op:'discover'}).sources[0];
  assert.notEqual(changed.id,old.id);
});

(function(){
"use strict";
var REPO="Kumolix/Mobilix-Launcher-for-Minecraft";
var T={
ru:{
 nav_install:"Установка",nav_changes:"Что нового",nav_faq:"Вопросы",
 h1:"Minecraft Java на вашем телефоне",
 lead:"Лаунчер со своим запуском Java 21, без PojavLauncher. Vanilla, Fabric и Forge, моды из Modrinth и экранное управление, которое можно настроить под себя.",
 download:"Скачать APK",dl_fallback:"Последняя версия на GitHub",req:"Android 9 и новее, arm64",
 slots_label:"Возможности",sh_t:"Как выглядит",sh1:"Главный экран",sh2:"Каталог модов",sh3:"Быстрые настройки графики",sh4:"Выбор версии игры",
 inst_t:"Как установить",
 s1h:"Скачайте APK",s1p:"Нажмите «Скачать APK» на этой странице. Файл появится в загрузках телефона.",
 s2h:"Разрешите установку",s2p:"Откройте файл. Android попросит разрешить установку из этого источника. Если Play Protect предупредит, выберите «Всё равно установить».",
 s3h:"Выберите версию игры",s3p:"В лаунчере укажите ник и версию Minecraft. Нужные файлы игры скачаются сами, поэтому на первый раз нужен интернет.",
 s4h:"Настройте графику",s4p:"Перед первой игрой откройте Настройки и выберите пресет графики. Для большинства телефонов лаунчер уже подбирает мипмапы и разрешение сам.",
 chg_t:"Что нового",loading:"Загружаем список версий…",chg_fail:"Не удалось загрузить список. Все версии есть на GitHub.",
 faq_t:"Вопросы и ответы",
 f_all:"Все версии",f_bug:"Сообщить об ошибке",f_priv:"Конфиденциальность",
 f_note:"Mobilix не связан с Mojang и Microsoft. Minecraft — товарный знак Mojang AB.",
 dl_n:["раз","раза","раз"],dl_pre:"Скачано",
 mb:"МБ",date:{locale:"ru-RU"},
 feats:[
  ["Виртуальная мышь","Курсор двигается пальцем, есть кнопки ЛКМ, ПКМ и прокрутка. Можно делить стаки, переносить по одному предмету и листать настройки."],
  ["Графика под ваш телефон","Лаунчер определяет видеочип и подбирает мипмапы и разрешение рендера. На старых Adreno это разница между 1 и 30 кадрами в секунду. Есть четыре быстрых пресета."],
  ["Каталог Modrinth","Моды, ресурспаки и шейдеры с фильтрами по версии и загрузчику. Если подходящей сборки нет, файл можно скачать вручную."],
  ["Fabric и Forge","Vanilla, Fabric и Forge, в том числе старые версии. Свой запуск Java 21, LWJGL и OpenGL поверх OpenGL ES."],
  ["Оффлайн и Ely.by","Играйте по нику без аккаунта или входите через Ely.by. Вход через Microsoft пока недоступен: Mojang не одобрил регистрацию приложения."],
  ["Управление под себя","Экранные кнопки с редактором раскладки, 3D-скин, настройки памяти и JVM, русский и английский язык."]
 ],
 faq:[
  ["Нужна ли лицензия Minecraft?","Нет. Можно играть по нику в оффлайн-режиме или войти через Ely.by. Вход через Microsoft реализован, но пока отключён."],
  ["Почему нет входа через Microsoft?","Для него Mojang должен одобрить регистрацию приложения. Заявку отклонили без объяснения причин. Когда приложение одобрят, вход включится обновлением."],
  ["Чем это отличается от PojavLauncher?","Mobilix на нём не основан: у него свой запуск Java 21, LWJGL, gl4es для версий 1.8–1.16.5 и MobileGlues для 1.17 и новее."],
  ["На каких телефонах работает?","Нужен Android 9 или новее и процессор arm64. Проверялся на Galaxy S24 FE, Galaxy A52 и планшете SM-T595. На старых моделях игра медленнее, автонастройка графики помогает."],
  ["Лаунчер собирает данные?","Нет. Подробности в политике конфиденциальности."],
  ["Безопасно ли ставить APK?","<p>Полной гарантии не даёт никто, но проверить можно самому. Вот факты.</p><ul><li>Исходный код открыт на GitHub, а APK собирает GitHub Actions из этого кода. Скачивайте только отсюда или из раздела Releases на GitHub: файлы из чатов и с чужих сайтов не наши.</li><li>Разрешения: интернет, служба переднего плана и уведомления (чтобы загрузка не прерывалась), блокировка сна на время загрузки. Контакты, местоположение, камера, микрофон, фото и файлы вне папки приложения недоступны.</li><li>Рекламы, аналитики и отчётов о сбоях нет. Ник, настройки и миры остаются на устройстве.</li><li>APK подписан постоянным ключом, поэтому новая версия ставится поверх старой и не теряет данные. Подделка с другой подписью поверх не установится.</li></ul><p>Android и Play Protect могут предупредить об установке. Так бывает с любым приложением не из Google Play, особенно новым и таким, которое запускает Java внутри себя. Само предупреждение не значит, что внутри вредоносный код.</p><p>Чтобы убедиться, загрузите APK на VirusTotal и посмотрите отчёт. Если не доверяете сборке, соберите её сами из исходников: инструкция в README репозитория.</p>"],
  ["Нашёл ошибку, куда писать?","В раздел Issues на GitHub или в Telegram. Приложите файл game.log, если игра вылетает."]
 ]
},
en:{
 nav_install:"Install",nav_changes:"What's new",nav_faq:"FAQ",
 h1:"Minecraft Java on your phone",
 lead:"A launcher with its own Java 21 runtime, no PojavLauncher. Vanilla, Fabric and Forge, Modrinth mods and on-screen controls you can lay out yourself.",
 download:"Download APK",dl_fallback:"Latest version on GitHub",req:"Android 9 or newer, arm64",
 slots_label:"Features",sh_t:"What it looks like",sh1:"Home screen",sh2:"Mod catalog",sh3:"Quick graphics presets",sh4:"Game version picker",
 inst_t:"How to install",
 s1h:"Download the APK",s1p:"Press “Download APK” on this page. The file lands in your phone's downloads.",
 s2h:"Allow the install",s2p:"Open the file. Android will ask to allow installs from this source. If Play Protect warns you, choose “Install anyway”.",
 s3h:"Pick a game version",s3p:"Enter a nickname and choose a Minecraft version in the launcher. Game files download on their own, so the first run needs internet.",
 s4h:"Set up graphics",s4p:"Before your first game open Settings and pick a graphics preset. On most phones the launcher already chooses mipmaps and render resolution for you.",
 chg_t:"What's new",loading:"Loading versions…",chg_fail:"Could not load the list. All versions are on GitHub.",
 faq_t:"FAQ",
 f_all:"All versions",f_bug:"Report a bug",f_priv:"Privacy",
 f_note:"Mobilix is not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang AB.",
 dl_n:["downloads","downloads","downloads"],dl_pre:"",
 mb:"MB",date:{locale:"en-GB"},
 feats:[
  ["Virtual mouse","Move the cursor with a finger, with left and right click and scroll buttons. Split stacks, move a single item and scroll through settings."],
  ["Graphics tuned to your phone","The launcher detects your GPU and picks mipmaps and render resolution. On older Adreno chips that is the difference between 1 and 30 FPS. Four quick presets included."],
  ["Modrinth catalog","Mods, resource packs and shaders with version and loader filters. If no matching build exists you can download the file by hand."],
  ["Fabric and Forge","Vanilla, Fabric and Forge, old versions included. Its own Java 21 runtime, LWJGL and OpenGL on top of OpenGL ES."],
  ["Offline and Ely.by","Play with just a nickname or sign in with Ely.by. Microsoft sign-in is unavailable for now: Mojang has not approved the app registration."],
  ["Controls your way","On-screen buttons with a layout editor, 3D skin, memory and JVM settings, Russian and English interface."]
 ],
 faq:[
  ["Do I need a Minecraft license?","No. You can play offline with a nickname or sign in with Ely.by. Microsoft sign-in is built but switched off for now."],
  ["Why is there no Microsoft sign-in?","Mojang has to approve the app registration first. The request was declined without an explanation. Once the app is approved, an update will turn it on."],
  ["How is this different from PojavLauncher?","Mobilix is not based on it. It has its own Java 21 launch, LWJGL, gl4es for 1.8–1.16.5 and MobileGlues for 1.17 and newer."],
  ["Which phones does it run on?","Android 9 or newer with an arm64 processor. Tested on a Galaxy S24 FE, a Galaxy A52 and an SM-T595 tablet. Older models are slower, and automatic graphics setup helps."],
  ["Does the launcher collect data?","No. See the privacy policy for details."],
  ["Is it safe to install the APK?","<p>Nobody can give a full guarantee, but you can check it yourself. Here are the facts.</p><ul><li>The source code is open on GitHub and the APK is built by GitHub Actions from that code. Download only from here or from GitHub Releases: files from chats or other sites are not ours.</li><li>Permissions: internet, a foreground service and notifications (so downloads are not interrupted), and a wake lock while downloading. Contacts, location, camera, microphone, photos and files outside the app folder are not accessible.</li><li>No ads, no analytics, no crash reports. Your nickname, settings and worlds stay on the device.</li><li>The APK is signed with a permanent key, so a new version installs over the old one and keeps your data. A fake signed with another key cannot install over it.</li></ul><p>Android and Play Protect may warn you before installing. That happens with any app that is not from Google Play, especially a new one that runs Java inside itself. The warning alone does not mean there is malicious code.</p><p>To be sure, upload the APK to VirusTotal and read the report. If you do not trust the build, compile it yourself from the source: see the repository README.</p>"],
  ["I found a bug, where do I report it?","In GitHub Issues or in Telegram. Attach game.log if the game crashes."]
 ]
}};

var DG={
"0":[".###.","#...#","#..##","#.#.#","##..#","#...#",".###."],
"1":["..#..",".##..","..#..","..#..","..#..","..#..",".###."],
"2":[".###.","#...#","....#","...#.","..#..",".#...","#####"],
"3":["####.","....#","....#",".###.","....#","....#","####."],
"4":["...#.","..##.",".#.#.","#..#.","#####","...#.","...#."],
"5":["#####","#....","#....","####.","....#","#...#",".###."],
"6":[".###.","#....","#....","####.","#...#","#...#",".###."],
"7":["#####","....#","....#","...##","...#.","...#.","...#."],
"8":[".###.","#...#","#...#",".###.","#...#","#...#",".###."],
"9":[".###.","#...#","#...#",".####","....#","....#",".###."],
".":[".",".",".",".",".",".","#"],
" ":["...","...","...","...","...","...","..."]
};
/* свои пиксельные цифры 5x7: шрифт Pixelify Sans рисует 1, 2, 5, 7 неудачно */
function pxnum(str,scale){
 var x=0,r="";
 str=String(str).replace(/[\u00a0\u202f\u2009]/g," ");
 for(var i=0;i<str.length;i++){
  var g=DG[str[i]];if(!g)continue;
  for(var y=0;y<7;y++)for(var k=0;k<g[y].length;k++)if(g[y][k]==="#")r+='<rect x="'+(x+k)+'" y="'+y+'" width="1" height="1"/>';
  x+=g[0].length+1;
 }
 x=Math.max(x-1,1);
 return '<svg class="pxn" role="img" aria-label="'+str+'" viewBox="0 0 '+x+' 7" width="'+(x*scale)+'" height="'+(7*scale)+'" fill="currentColor" shape-rendering="crispEdges">'+r+'</svg>';
}
var SPR={
 cursor:[".w......",".ww.....",".www....",".wwww...",".wwwww..",".wwww...",".w.ww...","...ww..."],
 chip:[".g.gg.g.","gggggggg",".gGGGGg.","ggGddGgg","ggGddGgg",".gGGGGg.","gggggggg",".g.gg.g."],
 chest:[".nnnnnn.","nnnnnnnn","nNNNNNNn","nnnyynnn","nnnyynnn","nNNNNNNn","nNNNNNNn","nnnnnnnn"],
 pick:[".ssssss.","ss.nn.ss","s..nn..s","...nn...","...nn...","...nn...","...nn...","...nn..."],
 heart:[".rr..rr.","rrrrrrrr","rrrrrrrr","rrrrrrrr",".rrrrrr.","..rrrr..","...rr...","........"],
 globe:["..bbbb..",".bGGbbb.","bGGGbbGb","bbGGbGGb","bbbbbGGb","bGGbbGbb",".bGGbbb.","..bbbb.."]
};
var COL={w:"#e6f0e8",g:"#2ea84f",G:"#5bd67b",d:"#14301d",y:"#f2a93b",n:"#9a6a35",N:"#5b3a1a",s:"#b9c7bd",r:"#e5484d",b:"#3f86d6"};
var ORDER=["cursor","chip","chest","pick","heart","globe"];
function sprite(k){
 var r="",rows=SPR[k];
 for(var y=0;y<8;y++)for(var x=0;x<8;x++){var c=rows[y][x];if(c!==".")r+='<rect x="'+x+'" y="'+y+'" width="1" height="1" fill="'+COL[c]+'"/>';}
 return '<svg viewBox="0 0 8 8" aria-hidden="true">'+r+'</svg>';
}

var lang="ru",sel=0,cache={};
function $(s,r){return (r||document).querySelector(s);}
function $$(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s));}

function pickLang(){
 var s=null;try{s=localStorage.getItem("lang");}catch(e){}
 if(s==="ru"||s==="en")return s;
 var n=(navigator.language||"ru").toLowerCase();
 return /^(ru|uk|be|kk)/.test(n)?"ru":"en";
}

function buildSlots(){
 var box=$(".slots");
 box.innerHTML=ORDER.map(function(k,i){
  return '<button type="button" class="slot px" role="tab" data-i="'+i+'" aria-selected="'+(i===sel)+'" aria-label="'+T[lang].feats[i][0]+'">'+sprite(k)+'<i>'+pxnum(i+1,2)+'</i></button>';
 }).join("");
 $$(".slot").forEach(function(b){b.addEventListener("click",function(){select(+b.dataset.i);});});
}
function select(i){
 sel=(i+6)%6;
 $$(".slot").forEach(function(b,j){b.setAttribute("aria-selected",j===sel);});
 var f=T[lang].feats[sel];
 $("#ptitle").textContent=f[0];$("#ptext").textContent=f[1];
}

function applyLang(l){
 $$(".sn").forEach(function(el){el.innerHTML=pxnum(el.dataset.n,4);});
 lang=l;document.documentElement.lang=l;
 try{localStorage.setItem("lang",l);}catch(e){}
 $$("[data-lang]").forEach(function(b){b.setAttribute("aria-pressed",b.dataset.lang===l);});
 $$("[data-i18n]").forEach(function(el){var v=T[l][el.dataset.i18n];if(v!==undefined)el.textContent=v;});
 $(".slots").setAttribute("aria-label",T[l].slots_label);
 document.title=l==="ru"?"Mobilix: Minecraft Java на Android":"Mobilix: Minecraft Java on Android";
 buildSlots();select(sel);
 $("#faqbox").innerHTML=T[l].faq.map(function(q,qi){return '<details><summary>'+q[0]+'</summary><div class="body">'+q[1]+(qi===4?' <a href="https://github.com/'+REPO+'/blob/main/PRIVACY.md">'+T[l].f_priv+'</a>':'')+'</div></details>';}).join("");
 renderStats();renderLog();
}

/* releases */
function fmtDate(iso){try{return new Date(iso).toLocaleDateString(T[lang].date.locale,{year:"numeric",month:"long",day:"numeric"});}catch(e){return iso.slice(0,10);}}
function notes(body){
 body=(body||"").replace(/\r/g,"");
 var m=body.search(/\*{0,2}RU\b[^\n]*:/);
 var en=m>-1?body.slice(0,m):body, ru=m>-1?body.slice(m):body;
 var part=lang==="ru"?ru:en;
 var items=part.split("\n").filter(function(l){return /^\s*[-*] /.test(l);}).map(function(l){return l.replace(/^\s*[-*] /,"");});
 return items;
}
function esc(s){return s.replace(/[&<>]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;"}[c];});}
function renderLog(){
 var box=$("#log"),rel=cache.releases;
 if(cache.failed){box.innerHTML='<p class="meta">'+T[lang].chg_fail+' <a href="https://github.com/'+REPO+'/releases">GitHub</a></p>';return;}
 if(!rel)return;
 var shown=rel.filter(function(r){return !r.draft;}).slice(0,8);
 box.innerHTML=shown.map(function(r,i){
  var v=(r.tag_name||"").replace(/^APK-/,"");
  var li=notes(r.body).map(function(x){return "<li>"+esc(x)+"</li>";}).join("");
  return '<details'+(i===0?" open":"")+'><summary><span class="ver">'+pxnum(v,3)+'</span><span class="date">'+fmtDate(r.published_at)+'</span></summary><div class="body"><ul>'+li+'</ul></div></details>';
 }).join("");
}
function renderStats(){
 var rel=cache.releases;if(!rel)return;
 var latest=rel.filter(function(r){return !r.draft&&!r.prerelease;})[0];
 var apk=latest&&(latest.assets||[]).filter(function(a){return /\.apk$/i.test(a.name);})[0];
 if(apk){
  var a=$("#dl");a.href=apk.browser_download_url;
  $("#dlinfo").textContent=latest.tag_name.replace(/^APK-/,"v")+" · "+(apk.size/1048576).toFixed(0)+" "+T[lang].mb;
 }
 var total=cache.total;
 if(typeof total==="number"){
  total=Math.max(0,total-(cache.owner||0));
  if(total>0){
   var form=new Intl.PluralRules(lang==="ru"?"ru":"en").select(total);
   var w=T[lang].dl_n[form==="one"?0:form==="few"?1:2];
   var c=$("#count");c.hidden=false;
   c.innerHTML=(T[lang].dl_pre?T[lang].dl_pre+" ":"")+'<span class="count">'+pxnum(total.toLocaleString(lang==="ru"?"ru-RU":"en-GB"),3)+'</span> '+w;
  }
 }
}
function sumDownloads(rel){var n=0;rel.filter(function(r){return !r.draft;}).slice(0,3).forEach(function(r){(r.assets||[]).forEach(function(a){n+=a.download_count||0;});});return n;}
function getJSON(u){return fetch(u,{cache:"no-cache"}).then(function(r){if(!r.ok)throw new Error(r.status);return r.json();});}

function load(){
 getJSON("config.json").then(function(c){cache.owner=+c.ownerDownloads||0;renderStats();},function(){});
 getJSON("https://api.github.com/repos/"+REPO+"/releases?per_page=30").then(function(rel){
  cache.releases=rel;cache.total=sumDownloads(rel);renderStats();renderLog();
 }).catch(function(){
  getJSON("stats.json").then(function(s){
   if(s.releases){cache.releases=s.releases;cache.total=sumDownloads(s.releases);}else cache.failed=true;
   renderStats();renderLog();
  }).catch(function(){cache.failed=true;renderLog();});
 });
}

document.addEventListener("keydown",function(e){
 if(e.ctrlKey||e.metaKey||e.altKey)return;
 var t=e.target;if(t&&/INPUT|TEXTAREA|SELECT/.test(t.tagName))return;
 if(e.key>="1"&&e.key<="6"){select(+e.key-1);}
});
$$("[data-lang]").forEach(function(b){b.addEventListener("click",function(){applyLang(b.dataset.lang);});});
applyLang(pickLang());
load();
})();

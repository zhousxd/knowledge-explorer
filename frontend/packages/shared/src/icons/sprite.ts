// 取值来源：prototype/styleguide.html 的 <svg width="0" ...><defs>…</defs></svg> 整块
// <defs> 内全部 23 个 <symbol> 原文复制（禁止改值），包装为导出常量
export const SPRITE_HTML = `<svg xmlns="http://www.w3.org/2000/svg" style="position:absolute;width:0;height:0" aria-hidden="true"><defs>
    <symbol id="i-back" viewBox="0 0 24 24"><path d="M15 5 8 12l7 7"/></symbol>
    <symbol id="i-chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></symbol>
    <symbol id="i-compass" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/></symbol>
    <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="M15.5 15.5 20 20"/></symbol>
    <symbol id="i-book" viewBox="0 0 24 24"><path d="M4 5.5C6.5 4 9.8 4 12 5.7c2.2-1.7 5.5-1.7 8-.2V19c-2.5-1.5-5.8-1.5-8 .2-2.2-1.7-5.5-1.7-8-.2z"/><path d="M12 5.7V19.2"/></symbol>
    <symbol id="i-scale" viewBox="0 0 24 24"><path d="M12 4v16M5 7.5h14M5 7.5 2.8 13h4.4zM19 7.5 16.8 13h4.4z"/></symbol>
    <symbol id="i-layers" viewBox="0 0 24 24"><path d="M12 3.5 20.5 8 12 12.5 3.5 8z"/><path d="M3.5 12.5 12 17l8.5-4.5M3.5 16.5 12 21l8.5-4.5"/></symbol>
    <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></symbol>
    <symbol id="i-lock" viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></symbol>
    <symbol id="i-check" viewBox="0 0 24 24"><path d="M5 13l5 5L19 7"/></symbol>
    <symbol id="i-alert" viewBox="0 0 24 24"><path d="M12 4 21 19H3z"/><path d="M12 10v4"/><path d="M12 16.8v.2"/></symbol>
    <symbol id="i-share" viewBox="0 0 24 24"><circle cx="6" cy="12" r="2.6"/><circle cx="17.5" cy="5.5" r="2.6"/><circle cx="17.5" cy="18.5" r="2.6"/><path d="M8.4 10.8l6.7-3.9M8.4 13.2l6.7 3.9"/></symbol>
    <symbol id="i-star" viewBox="0 0 24 24"><path d="M12 3.8l2.5 5.1 5.6.8-4 4 .9 5.6-5-2.7-5 2.7.9-5.6-4-4 5.6-.8z"/></symbol>
    <symbol id="i-clock" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.5 2"/></symbol>
    <symbol id="i-users" viewBox="0 0 24 24"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19.5c0-3 2.5-5.2 5.5-5.2s5.5 2.2 5.5 5.2"/><circle cx="16.8" cy="9.2" r="2.5"/><path d="M16.5 14.6c2.3.3 4 2.2 4 4.9"/></symbol>
    <symbol id="i-pen" viewBox="0 0 24 24"><path d="M4 20l1.5-4.5L17 4l3 3L8.5 18.5z"/><path d="M14.5 6.5l3 3"/></symbol>
    <symbol id="i-mountain" viewBox="0 0 24 24"><path d="M3 19 9.5 7.5l3.5 6 2.5-4L21 19z"/></symbol>
    <symbol id="i-temple" viewBox="0 0 24 24"><path d="M4 9h16M6 9c1-4 3.5-6 6-6s5 2 6 6"/><path d="M12 3v3"/><path d="M7 9v8M17 9v8M4.5 20.5h15"/><path d="M7 17c0 1.5 1.2 2.5 2.5 2.5S12 18.5 12 17c0 1.5 1.2 2.5 2.5 2.5S17 18.5 17 17"/></symbol>
    <symbol id="i-bowl" viewBox="0 0 24 24"><path d="M4 11h16c0 4.5-3.5 8-8 8s-8-3.5-8-8z"/><path d="M8.5 7.5c1-1.8 3-1.8 4 0M13 5.5c1-1.8 3-1.8 4 0"/><path d="M3 21h18"/></symbol>
    <symbol id="i-wave" viewBox="0 0 24 24"><circle cx="12" cy="12" r="1.6"/><path d="M8.5 15.5a5 5 0 0 1 0-7M15.5 8.5a5 5 0 0 1 0 7"/><path d="M5.8 18.2a9 9 0 0 1 0-12.4M18.2 5.8a9 9 0 0 1 0 12.4"/></symbol>
    <symbol id="i-home" viewBox="0 0 24 24"><path d="M4.5 10.5 12 4l7.5 6.5V20h-15z"/><path d="M9.5 20v-6h5v6"/></symbol>
    <symbol id="i-path" viewBox="0 0 24 24"><circle cx="6" cy="6" r="2.2"/><circle cx="18" cy="12" r="2.2"/><circle cx="8" cy="19" r="2.2"/><path d="M8 6.8c4 .8 4 4.4 7.8 5M7.3 8.1c-.4 4 0 8 0 8.8"/></symbol>
    <symbol id="i-me" viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.5"/><path d="M5 20c0-3.6 3-6 7-6s7 2.4 7 6"/></symbol>
</defs></svg>`;

export const SPRITE_IDS = ['i-back','i-chev','i-compass','i-search','i-book','i-scale','i-layers',
  'i-plus','i-lock','i-check','i-alert','i-share','i-star','i-clock','i-users','i-pen','i-mountain',
  'i-temple','i-bowl','i-wave','i-home','i-path','i-me'];

export function installSprite() {
  if (!document.getElementById('ke-icon-sprite')) {
    const wrap = document.createElement('div');
    wrap.id = 'ke-icon-sprite';
    wrap.innerHTML = SPRITE_HTML;
    document.body.prepend(wrap);
  }
}

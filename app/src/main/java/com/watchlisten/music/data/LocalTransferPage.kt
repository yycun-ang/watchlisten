package com.watchlisten.music.data

/** 同源页面只发送用户选择的 File，文件名与错误始终以 textContent 呈现。 */
internal val TRANSFER_PAGE = """
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>传歌到腕上听</title><style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;background:#080b0c;color:#f4f2ed}
body{max-width:600px;margin:0 auto;padding:32px 20px}h1{font-size:28px;letter-spacing:-.5px;margin:0 0 8px}p{color:#afb1ad;line-height:1.6}
.panel{background:#171b1c;padding:22px;border-radius:24px;margin:24px 0}label{display:block;margin-bottom:10px}
input{box-sizing:border-box;width:100%;font:inherit;color:inherit;border:1px solid #485054;border-radius:12px;background:#0c1011;padding:13px;margin-bottom:18px}
button{border:0;border-radius:22px;background:#c8dfe4;color:#142126;padding:13px 18px;font-family:inherit;font-size:14px;font-weight:600;cursor:pointer;min-height:44px}
button.secondary{background:#2b3437;color:#c8dfe4}button:disabled{opacity:.45;cursor:default}button:focus-visible,input:focus-visible{outline:2px solid #c8dfe4;outline-offset:3px}
.actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.actions button{white-space:nowrap;padding-inline:10px}#send{grid-column:1/-1}#status{white-space:pre-line;word-break:break-word;color:#c6d7c5;min-height:24px}
#tasks{padding:0;list-style:none;margin:16px 0}li{padding:16px 0;border-top:1px solid #30383b}li strong{display:block;font-size:15px;overflow-wrap:anywhere}
.detail{color:#afb1ad;font-size:13px;line-height:1.6;white-space:pre-line;margin:5px 0 8px}.error{color:#e4aaa5}.done{color:#c6d7c5}progress{display:block;width:100%;height:9px;accent-color:#c8dfe4;margin:10px 0}
.row-actions{display:flex;justify-content:flex-end;margin-top:8px}.row-actions button{font-size:12px;padding:6px 12px;min-height:36px;background:#2b3437;color:#c8dfe4}
small{display:block;color:#91958f;line-height:1.6}#empty{color:#91958f;font-size:14px}
</style><h1>音乐，随腕而行</h1><p>将你自己的音乐原文件带到手表，离线也能听。</p>
<div class="panel"><label for="code">手表上的 8 位验证码</label><input id="code" inputmode="numeric" autocomplete="off" maxlength="8" placeholder="输入验证码">
<label for="files">选择音乐文件</label><input id="files" type="file" accept="audio/*,.flac,.wma,.aiff,.alac" multiple>
<div class="actions"><button id="send">开始 / 继续</button><button id="retry" class="secondary">只重试失败项</button><button id="pause" class="secondary" disabled>暂停</button></div>
<p id="status" role="status" aria-live="polite">一次选择多首，逐首确认。网络中断后可继续当前文件。</p>
<p id="empty">选中的音乐会显示在这里。</p><ol id="tasks" aria-label="传歌任务"></ol>
<button id="clear" class="secondary">清除已完成记录</button></div>
<small>手机和手表需连接同一个 Wi-Fi。当前会话 15 分钟后自动关闭，关闭或更换网络后暂存进度会清理。
续传时请保留此页面，文件按已确认的完整分块继续；刷新页面需要重新选择文件。页面只传输你选择的文件，不读取手表音乐和酷狗账号。</small>
<script>
const files=document.querySelector('#files'),code=document.querySelector('#code'),send=document.querySelector('#send'),retry=document.querySelector('#retry'),pause=document.querySelector('#pause'),status=document.querySelector('#status'),list=document.querySelector('#tasks'),empty=document.querySelector('#empty'),clear=document.querySelector('#clear');
const CHUNK=512*1024,tasks=[];let running=false,pauseRequested=false,currentXhr=null;
function randomKey(){const value=new Uint8Array(16);if(!globalThis.crypto||!crypto.getRandomValues)throw new Error('请使用支持安全随机数的现代浏览器');crypto.getRandomValues(value);return Array.from(value,x=>x.toString(16).padStart(2,'0')).join('')}
function mb(bytes){return (bytes/1048576).toFixed(1)+' MB'}
function request(method,path,body,onProgress){return new Promise((resolve,reject)=>{const xhr=new XMLHttpRequest();currentXhr=xhr;xhr.open(method,path);xhr.setRequestHeader('X-Watch-Code',code.value.trim());xhr.timeout=method==='PUT'?45000:120000;if(body)xhr.setRequestHeader('Content-Type','application/octet-stream');
xhr.upload.onprogress=e=>{if(e.lengthComputable&&onProgress)onProgress(e.loaded)};
function failure(message,statusCode){const error=new Error(message);error.statusCode=statusCode||0;reject(error)}
xhr.onload=()=>{if(xhr.status===200){try{resolve(JSON.parse(xhr.responseText))}catch(error){failure('手表响应未确认，请重试任务')}}else failure(xhr.responseText||'传输未完成，请查看手表状态',xhr.status)};
xhr.onerror=()=>failure('连接中断，请确认手表会话仍开启后重试');xhr.ontimeout=()=>failure('响应超时，可重试任务确认进度');xhr.onabort=()=>failure('已暂停，稍后可继续');xhr.send(body||null)})}
function render(){list.replaceChildren();empty.hidden=tasks.length>0;for(const task of tasks){const item=document.createElement('li'),title=document.createElement('strong'),detail=document.createElement('div'),bar=document.createElement('progress');title.textContent=task.file.name;
const labels={queued:'等待传输',sending:'正在传输',checking:'确认断点',importing:'正在校验音频',paused:'已暂停',failed:'未完成',done:'已导入手表'};detail.className='detail'+(task.state==='failed'?' error':task.state==='done'?' done':'');
detail.textContent=labels[task.state]+' · '+mb(task.offset)+' / '+mb(task.file.size)+' 已确认'+(task.error?'\n'+task.error:'');bar.max=task.file.size||1;bar.value=Math.min(task.sent,task.file.size);task.progressView=bar;item.append(title,detail,bar);
if(!running&&task.state!=='done'){const actions=document.createElement('div'),remove=document.createElement('button');actions.className='row-actions';remove.textContent='移除任务';remove.onclick=()=>removeTask(task);actions.append(remove);item.append(actions)}list.append(item)}
send.disabled=running||!tasks.some(t=>t.state==='queued'||t.state==='paused');retry.disabled=running||!tasks.some(t=>t.state==='failed');pause.disabled=!running;files.disabled=running;code.disabled=running;clear.disabled=running||!tasks.some(t=>t.state==='done')}
files.onchange=()=>{try{for(const file of Array.from(files.files)){if(tasks.some(t=>t.file.name===file.name&&t.file.size===file.size&&t.file.lastModified===file.lastModified&&t.state!=='done'))continue;
tasks.push({file,key:randomKey(),id:null,offset:0,sent:0,state:file.size>0&&file.size<=2147483648?'queued':'failed',error:file.size===0?'文件为空':file.size>2147483648?'单个文件不能超过 2 GB':''})}files.value='';render()}catch(error){status.textContent=error.message}};
async function syncTask(task){if(!task.id){const state=await request('POST','/uploads?name='+encodeURIComponent(task.file.name)+'&size='+task.file.size+'&key='+task.key);task.id=state.id;return state}
try{return await request('GET','/uploads/'+task.id)}catch(error){if(error.statusCode!==404)throw error;task.id=null;return syncTask(task)}}
async function transfer(task){task.state='checking';task.error='';render();let state=await syncTask(task);task.offset=state.offset;task.sent=state.offset;
if(state.completed){task.state='done';render();return}
while(task.offset<task.file.size&&!pauseRequested){task.state='sending';render();const base=task.offset,chunk=task.file.slice(base,Math.min(base+CHUNK,task.file.size));state=await request('PUT','/uploads/'+task.id+'?offset='+base,chunk,bytes=>{task.sent=base+bytes;if(task.progressView)task.progressView.value=task.sent});task.offset=state.offset;task.sent=state.offset}
if(pauseRequested){task.state='paused';render();return}task.state='importing';render();state=await request('POST','/uploads/'+task.id+'/complete');if(!state.completed)throw new Error('尚未收到导入确认，请重试任务');task.offset=task.file.size;task.sent=task.file.size;task.state='done';render()}
async function run(failedOnly){if(!/^[0-9]{8}$/.test(code.value.trim())){status.textContent='请输入手表上的 8 位验证码';code.focus();return}
const selected=tasks.filter(t=>failedOnly?t.state==='failed':t.state==='queued'||t.state==='paused');if(!selected.length)return;running=true;pauseRequested=false;render();
try{for(const task of selected){if(pauseRequested)break;try{await transfer(task)}catch(error){task.sent=task.offset;task.state=pauseRequested?'paused':'failed';task.error=pauseRequested?'':error.message;render()}}}
finally{running=false;currentXhr=null;const done=tasks.filter(t=>t.state==='done').length,failed=tasks.filter(t=>t.state==='failed').length;status.textContent='已确认 '+done+' 首'+(failed?'，'+failed+' 首可单独重试':'')+(pauseRequested?'。传输已暂停。':'。');render()}}
async function removeTask(task){if(running)return;if(task.id){try{await request('DELETE','/uploads/'+task.id)}catch(error){if(error.statusCode!==404&&error.statusCode!==410){status.textContent='任务暂未移除：'+error.message;return}}}tasks.splice(tasks.indexOf(task),1);render()}
send.onclick=()=>run(false);retry.onclick=()=>run(true);pause.onclick=()=>{pauseRequested=true;if(currentXhr)currentXhr.abort()};clear.onclick=()=>{for(let i=tasks.length-1;i>=0;i--)if(tasks[i].state==='done')tasks.splice(i,1);render()};render();
</script></html>
""".trimIndent()

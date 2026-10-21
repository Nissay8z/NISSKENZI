import { CORE_URL, FFMessageType } from "./const.js";
import { ERROR_UNKNOWN_MESSAGE_TYPE, ERROR_NOT_LOADED, ERROR_IMPORT_FAILURE } from "./errors.js";

let ffmpeg;
async function load({coreURL:_coreURL, wasmURL:_wasmURL, workerURL:_workerURL}) {
  const first=!ffmpeg;
  try {
    if(!_coreURL) _coreURL=CORE_URL;
    importScripts(_coreURL);
  } catch {
    if(!_coreURL) _coreURL=CORE_URL.replace("/umd/","/esm/");
    self.createFFmpegCore=(await import(/* webpackIgnore: true */ _coreURL)).default;
    if(!self.createFFmpegCore) throw ERROR_IMPORT_FAILURE;
  }
  const coreURL=_coreURL;
  const wasmURL=_wasmURL?_wasmURL:_coreURL.replace(/.js$/g,".wasm");
  const workerURL=_workerURL?_workerURL:_coreURL.replace(/.js$/g,".worker.js");
  ffmpeg=await self.createFFmpegCore({mainScriptUrlOrBlob:`${coreURL}#${btoa(JSON.stringify({wasmURL,workerURL}))}`});
  ffmpeg.setLogger(data=>self.postMessage({type:FFMessageType.LOG,data}));
  ffmpeg.setProgress(data=>self.postMessage({type:FFMessageType.PROGRESS,data}));
  return first;
}
const exec=({args,timeout=-1})=>{ffmpeg.setTimeout(timeout);ffmpeg.exec(...args);const ret=ffmpeg.ret;ffmpeg.reset();return ret};
const writeFile=({path,data})=>{ffmpeg.FS.writeFile(path,data);return true};
const readFile=({path,encoding})=>ffmpeg.FS.readFile(path,{encoding});
const deleteFile=({path})=>{ffmpeg.FS.unlink(path);return true};
const rename=({oldPath,newPath})=>{ffmpeg.FS.rename(oldPath,newPath);return true};
const createDir=({path})=>{ffmpeg.FS.mkdir(path);return true};
const listDir=({path})=>ffmpeg.FS.readdir(path).map(name=>({name,isDir:ffmpeg.FS.isDir(ffmpeg.FS.stat(`${path}/${name}`).mode)}));
const deleteDir=({path})=>{ffmpeg.FS.rmdir(path);return true};
const mount=({fsType,options,mountPoint})=>{const fs=ffmpeg.FS.filesystems[fsType];if(!fs)return false;ffmpeg.FS.mount(fs,options,mountPoint);return true};
const unmount=({mountPoint})=>{ffmpeg.FS.unmount(mountPoint);return true};
self.onmessage=async({data:{id,type,data:_data}})=>{
  let data;
  try {
    if(type!==FFMessageType.LOAD&&!ffmpeg) throw ERROR_NOT_LOADED;
    switch(type){
      case FFMessageType.LOAD:data=await load(_data);break;
      case FFMessageType.EXEC:data=exec(_data);break;
      case FFMessageType.WRITE_FILE:data=writeFile(_data);break;
      case FFMessageType.READ_FILE:data=readFile(_data);break;
      case FFMessageType.DELETE_FILE:data=deleteFile(_data);break;
      case FFMessageType.RENAME:data=rename(_data);break;
      case FFMessageType.CREATE_DIR:data=createDir(_data);break;
      case FFMessageType.LIST_DIR:data=listDir(_data);break;
      case FFMessageType.DELETE_DIR:data=deleteDir(_data);break;
      case FFMessageType.MOUNT:data=mount(_data);break;
      case FFMessageType.UNMOUNT:data=unmount(_data);break;
      default:throw ERROR_UNKNOWN_MESSAGE_TYPE;
    }
  } catch(e) { self.postMessage({id,type:FFMessageType.ERROR,data:String(e)});return; }
  const trans=[];if(data instanceof Uint8Array)trans.push(data.buffer);
  self.postMessage({id,type,data},trans);
};
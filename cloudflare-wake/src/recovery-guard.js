import {ApiError} from './google.js';
export function recoveryGuard(value) {
  if(value==null)return null;
  if(!value || typeof value!=='object' || Array.isArray(value) || Object.keys(value).sort().join(',')!=='epoch,generation,operationId,revision' ||
    typeof value.revision!=='string' || typeof value.operationId!=='string' || !/^[0-9T:.Z-]{20,40}$/.test(value.revision) || !/^[a-f0-9-]{36}$/.test(value.operationId) ||
    !Number.isSafeInteger(value.epoch) || value.epoch<1 || !Number.isSafeInteger(value.generation) || value.generation<0)throw new ApiError('invalid_recovery_guard',400);
  return {revision:value.revision,epoch:value.epoch,generation:value.generation,operationId:value.operationId};
}
export function guardMatches(guard,family){
  if(guard && (family.updateTime!==guard.revision || family.epoch!==guard.epoch || (family.parentRebindGeneration||0)!==guard.generation))throw new ApiError('recovery_snapshot_changed',409);
}

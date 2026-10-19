import test from 'node:test';
import assert from 'node:assert/strict';
import {evidenceStage} from '../src/coordinator.js';
test('FCM receipt is not service/GPS evidence; old completion cannot finish newest command',()=>{
 assert.equal(evidenceStage({refreshReceivedFor:200,refreshServiceFor:100,refreshCompletedFor:100,locationTime:300,lastLat:20,lastLon:105},200),'FCM_RECEIVED');
 assert.equal(evidenceStage({refreshServiceFor:200,refreshAckFor:200},200),'SERVICE_STARTED');
});
test('server confirmation requires same request and actual fresh coordinate fields',()=>{
 assert.equal(evidenceStage({refreshCompletedFor:200,locationTime:199,lastLat:20,lastLon:105},200),'REQUESTED');
 assert.equal(evidenceStage({refreshCompletedFor:200,locationTime:260,lastLat:20,lastLon:105},200),'SERVER_CONFIRMED');
 assert.equal(evidenceStage({refreshFailedFor:200,refreshReceivedFor:200},200),'FAILED');
});

import assert from 'node:assert/strict';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {chromium} from 'playwright';

const root=path.resolve(import.meta.dirname,'..');
const htmlPath=path.join(root,'corex-v9.13.249','app','src','main','assets','index.html');
const browser=await chromium.launch({headless:true});

try{
 const page=await browser.newPage({viewport:{width:360,height:800},deviceScaleFactor:1});
 await page.goto(pathToFileURL(htmlPath).href,{waitUntil:'domcontentloaded'});
 await page.waitForSelector('#settingsGroupNav [data-settings-group="general"]');
 await page.waitForTimeout(350);

 const inspect=()=>page.evaluate(()=>{
  const expected={general:'General',reminders:'Alert',data:'Data',security:'Security',connection:'PC'};
  return Object.entries(expected).map(([id,label])=>{
   const button=document.querySelector(`#settingsGroupNav [data-settings-group="${id}"]`);
   const direct=[...button.children];
   const icon=button.querySelector(':scope > .settings-section-icon');
   const text=button.querySelector(':scope > .settings-section-label');
   return {
    id,
    label:text?.textContent?.trim(),
    childClasses:direct.map(node=>node.className),
    directChildren:direct.length,
    approvedMarker:button.dataset.mlmApprovedSettingsIcon,
    standardMarker:button.dataset.mlmStandardIcon,
    approvedIcons:button.querySelectorAll(':scope > .settings-section-icon').length,
    approvedSvgs:icon?.querySelectorAll('svg').length||0,
    labels:button.querySelectorAll(':scope > .settings-section-label').length,
    duplicateIcons:button.querySelectorAll(':scope > .mlm-standard-icon,:scope > .mlm-a-icon').length,
    expectedLabel:label
   };
  });
 });

 const assertClean=rows=>{
  assert.equal(rows.length,5);
  for(const row of rows){
   assert.equal(row.label,row.expectedLabel,`${row.id} label must remain complete`);
   assert.equal(row.directChildren,2,`${row.id} must contain only its icon box and label`);
   assert.deepEqual(row.childClasses,['settings-section-icon','settings-section-label']);
   assert.equal(row.approvedMarker,'1');
   assert.equal(row.standardMarker,'1');
   assert.equal(row.approvedIcons,1);
   assert.equal(row.approvedSvgs,1,`${row.id} premium icon box must not be empty`);
   assert.equal(row.labels,1);
   assert.equal(row.duplicateIcons,0,`${row.id} must not receive a converter icon`);
  }
 };

 assertClean(await inspect());
 for(const id of ['reminders','data','security','connection','general']){
  await page.evaluate(group=>document.querySelector(`#settingsGroupNav [data-settings-group="${group}"]`)?.click(),id);
  await page.waitForTimeout(80);
  assertClean(await inspect());
 }

 console.log('Rendered Settings navigation invariant passed at 360px');
}finally{
 await browser.close();
}

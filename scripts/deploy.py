#!/usr/bin/env python3
"""Friday-only deployment with a checkpoint before changes and a scoped rollback."""
import argparse,datetime,hashlib,json,os,pathlib,re,shlex,subprocess
os.umask(0o077)
p=argparse.ArgumentParser();p.add_argument('action',choices=['preview','activate','restore']);p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--port',default='5041');p.add_argument('--backups',required=True);p.add_argument('--checkpoint');p.add_argument('--apk');args=p.parse_args()
base=[args.adb,'-P',args.port,'-s',args.serial]
def run(*cmd,timeout=60):return subprocess.check_output(base+list(cmd),text=True,timeout=timeout).strip()
def shell(cmd):return run('shell',cmd)
if shell('getprop ro.product.device')!='G10' or shell('getprop ro.product.name')!='G10_4K_GB':raise SystemExit('TV identity differs from Friday; no changes made.')
new='home.friday.tv';new_service=new+'/'+new+'.HomeKeyService'
old_services={'com.gathin.tvhome/.HomeKeyService','com.gathin.tvhome/com.gathin.tvhome.HomeKeyService'}
def services():
 s=shell('settings get secure enabled_accessibility_services');return [] if s in ('','null') else s.split(':')
def set_services(values):
 values=list(dict.fromkeys(values));shell('settings put secure enabled_accessibility_services '+shlex.quote(':'.join(values)))
 shell('settings put secure accessibility_enabled '+('1' if values else '0'))
if args.action=='preview':
 folder=pathlib.Path(args.backups)/datetime.datetime.now().strftime('%Y%m%d-%H%M%S-before-friday-home');folder.mkdir(parents=True,mode=0o700)
 state={'services':services(),'home':shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME').splitlines()[-1]}
 for name,command in {'enabled':'pm list packages -e --user 0','disabled':'pm list packages -d --user 0','global':'settings list global','secure':'settings list secure','system':'settings list system','properties':'getprop'}.items():
  (folder/(name+'.txt')).write_text(shell(command)+'\n')
 (folder/'restore-state.json').write_text(json.dumps(state,indent=2)+'\n')
 apk=pathlib.Path(args.apk);digest=hashlib.sha256(apk.read_bytes()).hexdigest()
 run('push',str(apk),'/data/local/tmp/friday-home.apk',timeout=120)
 if shell('sha256sum /data/local/tmp/friday-home.apk').split()[0]!=digest:raise SystemExit('Transfer checksum mismatch; existing Home is unchanged.')
 result=shell('pm install -r /data/local/tmp/friday-home.apk');shell('rm -f /data/local/tmp/friday-home.apk')
 if result!='Success':raise SystemExit('Install not confirmed: '+result)
 shell('pm enable --user 0 '+new)
 grant=shell('pm grant '+new+' android.permission.READ_TV_LISTINGS')
 if grant:raise SystemExit('TV-listings permission not confirmed: '+grant)
 shell('cmd appops set '+new+' APP_AUTO_START allow');shell('appops write-settings')
 result=shell('am start -W -n '+new+'/.HomeActivity')
 if 'Status: ok' not in result:raise SystemExit('Preview did not start; old Home remains active.')
 print(json.dumps({'stage':'preview','checkpoint':str(folder),'apk_sha256':digest,'old_home_unchanged':True}))
else:
 if not args.checkpoint:raise SystemExit('--checkpoint is required')
 state=json.loads((pathlib.Path(args.checkpoint)/'restore-state.json').read_text())
 if args.action=='activate':
  shell('am start -W -n '+new+'/.HomeActivity')
  others=[s for s in services() if s not in old_services and not s.startswith(new+'/')]
  set_services(others+[new_service]);shell('appops write-settings')
  print('Friday Home helper activated. Existing apps and TCL services are unchanged; ADB remains enabled.')
 else:
  retained=[s for s in services() if not s.startswith(new+'/')]
  set_services(retained+state['services'])
  home=state['home']
  if not re.fullmatch(r'[\w.]+/[\w.$]+',home):raise SystemExit('Invalid saved Home component')
  shell('cmd package set-home-activity --user 0 '+home)
  shell('pm disable-user --user 0 '+new)
  if any(s in old_services for s in state['services']):shell('am start -W -n com.gathin.tvhome/.MainActivity')
  else:shell('input keyevent HOME')
  print('Previous Home configuration restored. Nuvio, existing apps and debugging access are retained.')

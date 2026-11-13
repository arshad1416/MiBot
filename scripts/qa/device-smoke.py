#!/usr/bin/env python3
"""Bounded, fixture-only UI sweep. Requires MiBot QA open at the QA Bot profile.
No random taps, permission dialogs, real pairing, or external links are exercised.
Usage: python3 scripts/qa/device-smoke.py SERIAL
"""
import os,re,subprocess,sys,time,xml.etree.ElementTree as ET
adb=os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')
serial=sys.argv[1]
def run(*a): return subprocess.check_output([adb,'-s',serial,*a],text=True)
def screen():
 run('shell','uiautomator','dump','/sdcard/mibot-qa.xml')
 root=ET.fromstring(run('shell','cat','/sdcard/mibot-qa.xml'))
 assert any(n.get('package')=='app.mibot.qa' for n in root.iter('node')), 'Outside QA; stopping'
 return root
def tap(label):
 nodes=[n for n in screen().iter('node') if label in (n.get('text'),n.get('content-desc'))]
 assert nodes, 'Missing safe target: '+label
 n=nodes[0]; x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.25)
for cycle in range(4):
 for target in ['Routines','Library','Computer','Apps']:
  tap(target); screen(); tap('Back')
  print('PASS',cycle,target,flush=True)
 # background / foreground lifecycle, without changing any phone setting
 run('shell','input','keyevent','3')
 run('shell','am','start','-n','app.mibot.qa/app.mibot.MainActivity')
 screen()
 print('PASS',cycle,'background-resume',flush=True)
print('Completed 16 safe destination visits and 4 background-resume cycles.')

import urllib.request
import json
import sys

req = urllib.request.Request('https://music.amazon.com/config.json', headers={'User-Agent': 'Mozilla/5.0'})
res = urllib.request.urlopen(req)
config = json.loads(res.read())

csrf = config.get('csrf', {}).get('token')
ts = config.get('csrf', {}).get('ts')
rnd = config.get('csrf', {}).get('rnd')
csrf_header = json.dumps({'interface': 'CSRFInterface.v1_0.CSRFHeaderElement', 'token': csrf, 'timestamp': ts, 'rndNonce': rnd})
headers = {'User-Agent': 'Mozilla/5.0', 'x-amzn-csrf': csrf_header, 'Content-Type': 'application/json'}
payload = json.dumps({'id': 'B074W3MJ26', 'userHash': '{"level":"LIBRARY_MEMBER"}'}).encode('utf-8')

req = urllib.request.Request('https://na.mesk.skill.music.a2z.com/api/cosmicTrack/displayCatalogTrack', data=payload, headers=headers)
try:
    data = urllib.request.urlopen(req).read().decode('utf-8')
    print(data)
except Exception as e:
    print("Error:", e)

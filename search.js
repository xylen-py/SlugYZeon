const https = require('https');

function request(url, options, postData) {
    return new Promise((resolve, reject) => {
        const req = https.request(url, options, (res) => {
            let body = '';
            res.on('data', chunk => body += chunk);
            res.on('end', () => resolve({statusCode: res.statusCode, body}));
        });
        req.on('error', reject);
        if (postData) req.write(postData);
        req.end();
    });
}

async function main() {
    const cfgRes = await request('https://music.amazon.com/config.json', {headers: {'User-Agent': 'Mozilla/5.0'}});
    const cfg = JSON.parse(cfgRes.body);
    const csrf = cfg.csrf;
    const csrfHeader = JSON.stringify({
        interface: 'CSRFInterface.v1_0.CSRFHeaderElement',
        token: csrf.token,
        timestamp: csrf.ts,
        rndNonce: csrf.rnd
    });
    
    const payload = JSON.stringify({
        keyword: 'tujhe bhula dia',
        userHash: JSON.stringify({level: 'LIBRARY_MEMBER'}),
        headers: JSON.stringify({
            'x-amzn-device-id': 'test-id',
            'x-amzn-user-agent': 'Mozilla/5.0',
            'x-amzn-session-id': 'test-session',
            'x-amzn-request-id': 'test-req',
            'x-amzn-device-language': 'en_US',
            'x-amzn-currency-of-preference': 'USD',
            'x-amzn-os-version': '1.0',
            'x-amzn-application-version': '1.0',
            'x-amzn-device-time-zone': 'UTC',
            'x-amzn-timestamp': Date.now().toString(),
            'x-amzn-csrf': csrfHeader,
            'x-amzn-music-domain': 'music.amazon.in',
            'x-amzn-page-url': 'https://music.amazon.in/search/tujhe+bhula+dia',
            'x-amzn-feature-flags': 'hd-supported,uhd-supported'
        })
    });

    const res = await request('https://na.mesk.skill.music.a2z.com/api/showSearch', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'User-Agent': 'Mozilla/5.0',
            'x-amzn-csrf': csrf.token,
            'x-amzn-authentication': JSON.stringify({
                interface: "ClientAuthenticationInterface.v1_0.ClientTokenElement",
                accessToken: ""
            })
        }
    }, payload);

    console.log(res.statusCode);
    require('fs').writeFileSync('search.json', res.body);
}

main().catch(console.error);

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate   = new Rate('error_rate');
const searchTrend = new Trend('search_ms', true);

const SEARCHES = [
  { lat: 1.35,  lng: 103.82, radius: 150, label: 'Singapore'  },
  { lat: 40.7,  lng: -74.0,  radius: 100, label: 'New York'   },
  { lat: 51.5,  lng: -0.1,   radius: 150, label: 'London'     },
  { lat: 35.6,  lng: 139.7,  radius: 100, label: 'Tokyo'      },
  { lat: 25.2,  lng: 55.3,   radius: 150, label: 'Dubai'      },
];

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 3,
      duration: '30s',
      tags: { scenario: 'smoke' },
    },
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m',  target: 50 },
        { duration: '30s', target: 0  },
      ],
      startTime: '35s',
      tags: { scenario: 'ramp_up' },
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    error_rate:        ['rate<0.1'],
    search_ms:         ['p(95)<2000'],
  },
};

export default function () {
  const search = SEARCHES[Math.floor(Math.random() * SEARCHES.length)];

  const url = `http://localhost:8080/api/v1/aircraft` +
              `?lat=${search.lat}&lng=${search.lng}&radius=${search.radius}`;

  const start = Date.now();
  const res   = http.get(url, { tags: { region: search.label } });
  searchTrend.add(Date.now() - start);

  check(res, {
    'status 200 or 429':     (r) => r.status === 200 || r.status === 429,
    'response under 3s':     (r) => r.timings.duration < 3000,
    'has rate limit header': (r) => r.headers['X-Rate-Limit-Remaining'] !== undefined,
  });

  errorRate.add(res.status !== 200 && res.status !== 429);
  sleep(Math.random() * 0.5);
}

export function handleSummary(data) {
  const reqs   = data.metrics.http_reqs.values.count;
  const p95    = data.metrics.http_req_duration.values['p(95)'].toFixed(0);
  const p99    = data.metrics.http_req_duration.values['p(99)'].toFixed(0);
  const errors = data.metrics.error_rate
                  ? (data.metrics.error_rate.values.rate * 100).toFixed(1)
                  : '0.0';

  console.log('\n========== LOAD TEST SUMMARY ==========');
  console.log(`Total requests : ${reqs}`);
  console.log(`p95 latency    : ${p95}ms`);
  console.log(`p99 latency    : ${p99}ms`);
  console.log(`Error rate     : ${errors}%`);
  console.log('=======================================\n');

  return { stdout: JSON.stringify(data, null, 2) };
}

"""Run actual statistics HTTP controller/service in a 1.25 CPU / 2 GiB container.

Run benchmark.py first and compile the current engine classes with Maven.
Uses the existing engine JAR only as a source of dependency JARs; production
classes come from target/classes, so uncommitted fixes are exercised.
"""
import argparse
import concurrent.futures
import http.client
import hashlib
import datetime
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import zipfile

from benchmark import command

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres', default='gem128-statistics-pg17')
    parser.add_argument('--container', default='gem128-statistics-engine')
    parser.add_argument('--port', default='55439')
    parser.add_argument('--image', default='sentinel-gem-engine:latest')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    target = ROOT / 'engine/target'
    libraries = target / 'gem128-libs'
    libraries.mkdir(exist_ok=True)
    with zipfile.ZipFile(target / 'sentinel-gem-engine-0.0.1-SNAPSHOT.jar') as jar:
        for name in jar.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries / Path(name).name).write_bytes(jar.read(name))
    command('javac', '--release', '21', '-cp', f'{target / "classes"};{libraries}/*',
            '-d', str(target / 'gem128-probe'), str(Path(__file__).with_name('StatisticsProbe.java')))
    fixture = Path(__file__).with_name('fixture.sql').read_text(encoding='utf-8')
    command('docker', 'exec', '-i', args.postgres, 'psql', '-Xq', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres',
            stdin='DROP SCHEMA IF EXISTS gem128_probe CASCADE; CREATE SCHEMA gem128_probe; SET search_path TO gem128_probe;\n' + fixture)
    existing = command('docker', 'ps', '-a', '--filter', f'name=^{args.container}$', '--format', '{{.Names}}')
    if existing:
        command('docker', 'rm', '-f', args.container)
    network = 'gem128-statistics-network'
    if not command('docker', 'network', 'ls', '--filter', f'name=^{network}$', '--format', '{{.Name}}'):
        command('docker', 'network', 'create', network)
    connected = command('docker', 'inspect', '--format', '{{json .NetworkSettings.Networks}}', args.postgres)
    if network not in json.loads(connected):
        command('docker', 'network', 'connect', network, args.postgres)
    command('docker', 'run', '-d', '--name', args.container, '--cpus', '1.25', '--memory', '2g', '--memory-swap', '2g',
            '--network', network,
            '-p', f'127.0.0.1:{args.port}:8080', '-e',
            f'GEM128_JDBC_URL=jdbc:postgresql://{args.postgres}:5432/postgres',
            '-v', f'{target}:/work:ro', '--entrypoint', 'java', args.image,
            '-Xmx1536m', '-cp', '/work/classes:/work/gem128-probe:/work/gem128-libs/*',
            'com.example.globalnewsenginev1.statistics.StatisticsProbe',
            '--stories.visible-version=visible',
            '--spring.config.location=optional:file:/nonexistent', '--server.servlet.context-path=/api',
            '--spring.flyway.enabled=false', '--spring.jpa.hibernate.ddl-auto=none', '--spring.sql.init.mode=never')
    url = f'http://127.0.0.1:{args.port}/api/statistics'
    # The invalid parameter provides a fast HTTP readiness check without warming the report.
    for _ in range(90):
        try:
            urllib.request.urlopen(url + '?unsupported=x', timeout=1)
        except urllib.error.HTTPError as error:
            if error.code == 400:
                break
        except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected):
            pass
        time.sleep(1)
    else:
        raise RuntimeError(command('docker', 'logs', '--tail', '30', args.container))
    results = {'engine': {'cpus': 1.25, 'memoryMiB': 2048, 'heapMiB': 1536},
               'postgres': {'cpus': 0.75, 'memoryMiB': 1152}, 'requests': []}
    results['measuredAtUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    results['postgresVersion'] = command('docker', 'exec', args.postgres, 'psql', '-XqAt', '-U', 'postgres', '-c', 'SELECT version()')
    results['engineImageId'] = command('docker', 'inspect', '--format', '{{.Image}}', args.container)
    results['productionClassSha256'] = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in (target / 'classes/com/example/globalnewsenginev1/statistics').glob('*.class')
    }
    for attempt in range(3):
        def request():
            started = time.monotonic()
            with urllib.request.urlopen(url, timeout=60) as response:
                body = json.load(response)
                status = response.status
            metrics = {metric['id']: metric['value'] for metric in body['metrics']}
            assert status == 200 and metrics['articles'] == 194002 and metrics['processedArticles'] == 194002, metrics
            return {'status': status, 'seconds': round(time.monotonic() - started, 3),
                    'articles': metrics['articles'], 'processedArticles': metrics['processedArticles']}

        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(request)
            time.sleep(0.1)
            started = time.monotonic()
            count = command('docker', 'exec', args.postgres, 'psql', '-XqAt', '-U', 'postgres', '-c',
                            'SELECT COUNT(*) FROM gem128_probe.articles')
            assert count == '194002', count
            concurrent_seconds = round(time.monotonic() - started, 3)
            api_results = []
            for path in ('articles?limit=20', 'stories?limit=20'):
                api_started = time.monotonic()
                with urllib.request.urlopen(url.rsplit('/', 1)[0] + '/' + path, timeout=10) as response:
                    response.read()
                    assert response.status == 200
                    api_results.append({'path': path, 'status': response.status,
                                        'seconds': round(time.monotonic() - api_started, 3)})
            resources = command('docker', 'stats', '--no-stream', '--format',
                                '{{.Name}} {{.CPUPerc}} {{.MemUsage}}', args.container, args.postgres)
            result = pending.result()
            result['concurrentArticleCountSeconds'] = concurrent_seconds
            result['concurrentApis'] = api_results
            result['resourceSample'] = resources.splitlines()
        assert result['seconds'] < 45, result
        results['requests'].append(result)
        print(json.dumps(result), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

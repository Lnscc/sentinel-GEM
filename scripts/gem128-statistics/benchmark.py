"""Run the exact pre-GEM-128 count against a disposable, resource-limited PostgreSQL.

Requires Docker Desktop. The named container must not already exist. It is kept
until --remove is supplied so the optimized Java implementation can use it too.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent


def command(*args, stdin=None, timeout=180):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', default='gem128-statistics')
    parser.add_argument('--port', default='55438')
    parser.add_argument('--image', default='postgres:17')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--remove', action='store_true')
    args = parser.parse_args()
    # No remote host or production database option is accepted.
    command('docker', 'run', '-d', '--name', args.container, '--cpus', '0.75',
            '--memory', '1152m', '--memory-swap', '1152m', '-e',
            'POSTGRES_PASSWORD=gem128-local-only', '-p', f'127.0.0.1:{args.port}:5432',
            args.image, '-c', 'shared_buffers=128MB', '-c', 'work_mem=4MB')
    try:
        for _ in range(60):
            try:
                command('docker', 'exec', args.container, 'pg_isready', '-U', 'postgres', timeout=5)
                break
            except RuntimeError:
                time.sleep(1)
        else:
            raise RuntimeError('Isolated PostgreSQL did not become ready')

        def sql(statement, timeout=180):
            return command('docker', 'exec', '-i', args.container, 'psql', '-X', '-qAt',
                           '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', stdin=statement, timeout=timeout)

        sql(ROOT.joinpath('fixture.sql').read_text(encoding='utf-8'))
        query = """
            SELECT COUNT(DISTINCT member.article_id) FROM story_snapshot_statistics_members member
            WHERE member.clustering_version_id = 1
              AND member.snapshot_id = ANY(CAST((
                SELECT ARRAY_AGG(run.snapshot_id) FROM story_processing_runs run
                WHERE run.clustering_version_id = 1 AND run.status = 'SUCCEEDED'
                  AND run.completed_at >= '{start}' AND run.completed_at < '{end}'
              ) AS BIGINT ARRAY))
        """
        results = {'postgres': sql('SELECT version()'), 'container': args.container,
                   'resources': {'cpus': 0.75, 'memoryMiB': 1152},
                   'inputs': 194002, 'archivedSnapshots': 267, 'cases': []}
        for name, start, end, expected in [
            ('24h', '2026-11-27T12:00:00Z', '2026-11-28T12:00:00Z', 194002),
            ('partial-and-current', '2026-11-28T11:00:00Z', '2026-11-28T12:00:00Z', 3),
            ('empty', '2026-11-28T11:01:00Z', '2026-11-28T12:00:00Z', 0),
        ]:
            started = time.monotonic()
            try:
                count = int(sql("SET statement_timeout='120s';\n" + query.format(start=start, end=end)))
                assert count == expected, (name, count, expected)
                result = {'case': name, 'count': count, 'expected': expected}
            except RuntimeError as error:
                if 'statement timeout' not in str(error):
                    raise
                result = {'case': name, 'expected': expected, 'error': 'statement timeout (120s)'}
            result['seconds'] = round(time.monotonic() - started, 3)
            results['cases'].append(result)
            print(json.dumps(result), flush=True)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
        print(f'JDBC: jdbc:postgresql://127.0.0.1:{args.port}/postgres (postgres / gem128-local-only)')
    finally:
        if args.remove:
            command('docker', 'rm', '-f', args.container)


if __name__ == '__main__':
    main()

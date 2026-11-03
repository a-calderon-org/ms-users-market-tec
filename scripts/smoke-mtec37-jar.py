#!/usr/bin/env python3
"""Local JAR smoke: loopback only, local persistence, no Azure/SQL connection."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def available_ports():
    # Keep both reservations until distinct ports have been selected.
    with socket.socket() as main, socket.socket() as management:
        main.bind(('127.0.0.1', 0))
        management.bind(('127.0.0.1', 0))
        return main.getsockname()[1], management.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '')) / 'bin/java')
                        if os.environ.get('JAVA_HOME') else 'java')
    parser.add_argument('--evidence-dir', type=Path, default=ROOT / 'target/reqa-jar-smoke')
    args = parser.parse_args()
    evidence = args.evidence_dir.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    jar = ROOT / 'target/ms-users-0.1.0-SNAPSHOT.jar'
    result = dict(outcome='FAIL', profile='local', sql_executed=False, checks=[])
    process = None
    try:
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        port, management_port = available_ports()
        result.update(main_port=port, management_port=management_port)
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('SPRING_', 'USERS_SQL_', 'MANAGEMENT_', 'LOGGING_'))}
        command = [args.java, '-Xmx256m', '-jar', str(jar), '--spring.profiles.active=local',
                   '--spring.config.location=optional:classpath:/', '--server.address=127.0.0.1',
                   f'--server.port={port}', '--management.server.address=127.0.0.1',
                   f'--management.server.port={management_port}']
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def request(path, management=False, correlation='jar-smoke'):
            selected_port = management_port if management else port
            headers = {'X-Correlation-ID': correlation, 'Authorization': 'Bearer smoke-private-marker',
                       'Cookie': 'session=smoke-private-marker'}
            request = urllib.request.Request(f'http://127.0.0.1:{selected_port}{path}', headers=headers)
            try:
                response = opener.open(request, timeout=2)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, response.headers, response.read().decode('utf-8')

        with (evidence / 'jar.log').open('w') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 60
            while True:
                require(process.poll() is None, 'JAR exited before HTTP startup')
                try:
                    status, _, _ = request('/health')
                    if status == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                require(time.monotonic() < deadline, 'JAR did not become healthy within 60 seconds')
                # Bounded readiness polling, not a fixed delay that assumes startup completion.
                threading.Event().wait(0.1)

            def check(path, expected, management=False, correlation='jar-smoke'):
                status, headers, body = request(path, management, correlation)
                require(status == expected, f'{path}: expected HTTP {expected}, got {status}')
                parsed = json.loads(body)
                require('smoke-private-marker' not in body, f'{path}: sensitive information in response')
                if not management:
                    actual = headers.get('X-Correlation-ID')
                    if correlation == 'jar-smoke':
                        require(actual == correlation, f'{path}: correlation was not propagated')
                    else:
                        import uuid
                        require(str(uuid.UUID(actual)) == actual, 'Invalid correlation was not replaced by a UUID')
                    if expected >= 400:
                        require(parsed['error']['correlationId'] == actual, 'Error correlation does not match response')
                else:
                    require('components' not in parsed and 'details' not in parsed, 'Management details exposed')
                    if expected == 200:
                        expected_health = ({'status': 'UP', 'groups': ['liveness', 'readiness']}
                                           if path == '/actuator/health' else {'status': 'UP'})
                        require(parsed == expected_health, f'{path}: unexpected health payload {parsed}')
                result['checks'].append(dict(listener='management' if management else 'main',
                                             path=path, http_status=status))

            check('/health', 200)
            check('/health', 200, correlation='x' * 129)
            for path in ('/actuator/health', '/actuator/health/readiness', '/actuator/health/liveness',
                         '/readyz', '/livez', '/smoke-private-marker?token=smoke-private-marker'):
                check(path, 404)
            for path in ('/actuator/health', '/actuator/health/readiness', '/actuator/health/liveness'):
                check(path, 200, management=True)
            for path in ('/actuator/env', '/actuator/metrics'):
                check(path, 404, management=True)
            process.terminate()
            process.wait(timeout=20)
        lines = (evidence / 'jar.log').read_text().splitlines()
        events = [json.loads(line) for line in lines if line.strip()]
        require(any(event.get('message') == 'request_completed' and event.get('correlationId') == 'jar-smoke'
                    for event in events), 'Missing correlated completion JSON log')
        require('smoke-private-marker' not in '\n'.join(lines), 'Sensitive information in JAR logs')
        result.update(outcome='PASS', json_log_events=len(events))
    except Exception as error:
        result['error'] = str(error)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        (evidence / 'results.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['outcome'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())

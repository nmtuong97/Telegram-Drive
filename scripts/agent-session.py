#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone

def get_repo_root():
    try:
        return subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], stderr=subprocess.DEVNULL).decode().strip()
    except subprocess.CalledProcessError:
        print("ERROR: Not in a git repository.", file=sys.stderr)
        sys.exit(1)

REPO_ROOT = get_repo_root()
GIT_DIR = os.path.join(REPO_ROOT, '.git')
SESSIONS_DIR = os.path.join(GIT_DIR, 'agent-sessions')
LOCK_DIR = os.path.join(SESSIONS_DIR, 'active.lock')
REFS_DIR = os.path.join(GIT_DIR, 'refs', 'agent-sessions')

class AgentSessionError(Exception):
    pass

class SessionLock:
    def __init__(self, session_id):
        self.session_id = session_id
        self.locked = False

    def acquire(self):
        os.makedirs(SESSIONS_DIR, exist_ok=True)
        try:
            os.mkdir(LOCK_DIR)
            self._write_lock_info()
            self.locked = True
        except FileExistsError:
            pid_file = os.path.join(LOCK_DIR, 'pid')
            if os.path.exists(pid_file):
                try:
                    with open(pid_file, 'r') as f:
                        pid = int(f.read().strip())
                    # Check if pid is still running
                    os.kill(pid, 0)
                    raise AgentSessionError(f"Another agent session mutation is active (PID {pid}).")
                except ProcessLookupError:
                    # Stale lock
                    print(f"Cleaning up stale lock for PID {pid}...", file=sys.stderr)
                    shutil.rmtree(LOCK_DIR, ignore_errors=True)
                    os.mkdir(LOCK_DIR)
                    self._write_lock_info()
                    self.locked = True
                except (ValueError, OSError):
                    raise AgentSessionError("Failed to read active lock. Is another session crashing?")
            else:
                raise AgentSessionError("Lock directory exists but no PID file found.")

    def _write_lock_info(self):
        with open(os.path.join(LOCK_DIR, 'pid'), 'w') as f:
            f.write(str(os.getpid()))
        with open(os.path.join(LOCK_DIR, 'session-id'), 'w') as f:
            f.write(self.session_id)
        with open(os.path.join(LOCK_DIR, 'started-at'), 'w') as f:
            f.write(datetime.now(timezone.utc).isoformat())

    def release(self):
        if self.locked:
            try:
                pid_file = os.path.join(LOCK_DIR, 'pid')
                if os.path.exists(pid_file):
                    with open(pid_file, 'r') as f:
                        pid = int(f.read().strip())
                    if pid == os.getpid():
                        shutil.rmtree(LOCK_DIR, ignore_errors=True)
            except Exception:
                pass
            self.locked = False

    def __enter__(self):
        self.acquire()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.release()

def setup_signals():
    def handler(signum, frame):
        sys.exit(128 + signum)
    signal.signal(signal.SIGINT, handler)
    signal.signal(signal.SIGTERM, handler)

def run_git(*args):
    result = subprocess.run(['git'] + list(args), cwd=REPO_ROOT, capture_output=True, text=True)
    if result.returncode != 0:
        raise AgentSessionError(f"Git command failed: git {' '.join(args)}\n{result.stderr}")
    return result.stdout.strip()

def check_git_clean():
    if run_git('status', '--porcelain'):
        raise AgentSessionError("Working tree is dirty. Commit or stash changes first.")

def get_head_sha():
    return run_git('rev-parse', 'HEAD')

def get_current_branch():
    try:
        return run_git('symbolic-ref', '--short', 'HEAD')
    except AgentSessionError:
        raise AgentSessionError("Detached HEAD state is not supported.")

def check_rebase_merge_state():
    for state_dir in ['rebase-merge', 'rebase-apply', 'MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'BISECT_LOG']:
        if os.path.exists(os.path.join(GIT_DIR, state_dir)):
            raise AgentSessionError(f"Git is in the middle of an operation ({state_dir}). Please finish or abort it.")

def get_session_dir(session_id):
    return os.path.join(SESSIONS_DIR, session_id)

def load_state(session_id):
    state_file = os.path.join(get_session_dir(session_id), 'state.json')
    if not os.path.exists(state_file):
        raise AgentSessionError(f"Session {session_id} not found.")
    with open(state_file, 'r') as f:
        return json.load(f)

def save_state(session_id, state):
    session_dir = get_session_dir(session_id)
    os.makedirs(session_dir, exist_ok=True)
    state['updated_at'] = datetime.now(timezone.utc).isoformat()
    state_file = os.path.join(session_dir, 'state.json')
    
    fd, temp_path = tempfile.mkstemp(dir=session_dir, prefix='state.json.tmp')
    with os.fdopen(fd, 'w') as f:
        json.dump(state, f, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temp_path, state_file)

def set_ref(session_id, ref_type, sha):
    ref_name = f"refs/agent-sessions/{session_id}/{ref_type}"
    run_git('update-ref', ref_name, sha)
    return ref_name

def get_ref(session_id, ref_type):
    ref_name = f"refs/agent-sessions/{session_id}/{ref_type}"
    try:
        return run_git('rev-parse', '--verify', ref_name)
    except AgentSessionError:
        return None

def validate_commits(session_id, base_sha, head_sha, expected_branch):
    if base_sha == head_sha:
        return True, "No new commits found."

    current_branch = get_current_branch()
    if current_branch != expected_branch:
        raise AgentSessionError(f"Branch changed from {expected_branch} to {current_branch}.")

    try:
        merge_base = run_git('merge-base', base_sha, head_sha)
        if merge_base != base_sha:
            raise AgentSessionError("History rewrite detected. BASE is no longer an ancestor of HEAD.")
    except AgentSessionError:
        raise AgentSessionError("Failed to compute merge-base. History might be rewritten.")

    commits = run_git('log', f"{base_sha}..{head_sha}", '--format=%H').split()
    if not commits:
        return True, "No commits to validate."

    for commit in commits:
        body = run_git('log', '-1', '--format=%B', commit)
        if f"Agent-Session: {session_id}" not in body:
            raise AgentSessionError(f"Commit {commit} is missing trailer 'Agent-Session: {session_id}'.")
        if "Agent: Antigravity" not in body:
            raise AgentSessionError(f"Commit {commit} is missing trailer 'Agent: Antigravity'.")

    return True, "Commits validated successfully."

def invoke_agy(task_file, session_dir, is_continue=False):
    agy_bin = os.environ.get('AGY_BIN', 'agy')
    if not shutil.which(agy_bin):
        raise AgentSessionError(f"Antigravity CLI '{agy_bin}' not found in PATH.")

    log_file = os.path.join(session_dir, 'antigravity.jsonl')
    
    cmd = [agy_bin, '--headless', '--jsonl']
    if is_continue:
        cmd.extend(['--continue', task_file])
    else:
        cmd.extend(['--goal', task_file])

    print(f"Invoking {agy_bin} (logs to {log_file})...")
    
    # Simple runner bridging stdout
    with open(log_file, 'a') as lf:
        proc = subprocess.Popen(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            for line in proc.stdout:
                sys.stdout.write(line)
                lf.write(line)
                lf.flush()
            proc.wait(timeout=3600) # 1 hour hard timeout
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            raise AgentSessionError("timeout")
        except KeyboardInterrupt:
            proc.send_signal(signal.SIGTERM)
            proc.wait()
            raise AgentSessionError("interrupted")
        
    if proc.returncode != 0:
        return "implementation_failed", proc.returncode
    return "completed", 0

def cmd_start(args):
    if not os.path.exists(args.task_file) or os.path.getsize(args.task_file) == 0:
        raise AgentSessionError(f"Task file {args.task_file} does not exist or is empty.")

    check_git_clean()
    check_rebase_merge_state()
    branch = get_current_branch()
    base_sha = get_head_sha()

    with SessionLock(args.session):
        if os.path.exists(get_session_dir(args.session)):
            raise AgentSessionError(f"Session {args.session} already exists.")

        set_ref(args.session, 'base', base_sha)

        state = {
            "session_id": args.session,
            "status": "started",
            "branch": branch,
            "base": base_sha,
            "result": None,
            "reviewed": None,
            "conversation_id": None,
            "review_round": 0,
            "executor": "Antigravity",
            "reviewer": "Codex",
            "started_at": datetime.now(timezone.utc).isoformat()
        }
        
        session_dir = get_session_dir(args.session)
        os.makedirs(session_dir, exist_ok=True)
        shutil.copy2(args.task_file, os.path.join(session_dir, 'task.md'))
        save_state(args.session, state)

        try:
            status, code = invoke_agy(os.path.join(session_dir, 'task.md'), session_dir)
            
            # Re-check git state
            check_git_clean()
            head_sha = get_head_sha()
            validate_commits(args.session, base_sha, head_sha, branch)
            
            set_ref(args.session, 'result', head_sha)
            state['result'] = head_sha
            state['status'] = status
            save_state(args.session, state)
            print(f"Session {args.session} finished with status: {status}")
            
        except AgentSessionError as e:
            state['status'] = str(e)
            save_state(args.session, state)
            raise e
        except Exception as e:
            state['status'] = "process_crash"
            save_state(args.session, state)
            raise e

def cmd_continue(args):
    if not os.path.exists(args.review_file):
        raise AgentSessionError(f"Review file {args.review_file} does not exist.")
    
    with SessionLock(args.session):
        state = load_state(args.session)
        if state['status'] not in ['completed', 'implementation_failed']:
            raise AgentSessionError(f"Cannot continue session in status: {state['status']}")
            
        if state['review_round'] >= int(os.environ.get('MAX_REVIEW_ROUNDS', 2)):
            raise AgentSessionError("Max review rounds exceeded.")

        check_git_clean()
        check_rebase_merge_state()
        
        current_head = get_head_sha()
        if current_head != state.get('result'):
            raise AgentSessionError("HEAD must be at the current result ref to continue.")

        state['review_round'] += 1
        state['status'] = "review_continuing"
        save_state(args.session, state)

        session_dir = get_session_dir(args.session)
        review_dest = os.path.join(session_dir, f"review-round-{state['review_round']}.md")
        shutil.copy2(args.review_file, review_dest)

        try:
            status, code = invoke_agy(review_dest, session_dir, is_continue=True)
            
            check_git_clean()
            new_head = get_head_sha()
            validate_commits(args.session, current_head, new_head, state['branch'])
            
            set_ref(args.session, 'result', new_head)
            state['result'] = new_head
            state['status'] = status
            save_state(args.session, state)
            print(f"Session {args.session} continue finished with status: {status}")
            
        except AgentSessionError as e:
            state['status'] = str(e)
            save_state(args.session, state)
            raise e

def cmd_status(args):
    try:
        state = load_state(args.session)
        print(json.dumps(state, indent=2))
    except AgentSessionError as e:
        print(e, file=sys.stderr)
        sys.exit(1)

def cmd_accept(args):
    with SessionLock(args.session):
        state = load_state(args.session)
        if state['status'] not in ['completed', 'accepted']:
            raise AgentSessionError(f"Cannot accept session in status: {state['status']}")
            
        if not state.get('result'):
            raise AgentSessionError("Session has no result ref.")
            
        check_git_clean()
        
        current_head = get_head_sha()
        if current_head != state['result']:
            raise AgentSessionError("HEAD is not at the session result ref.")
            
        branch = get_current_branch()
        if branch != state['branch']:
            raise AgentSessionError(f"Not on the correct branch (expected {state['branch']}).")
            
        set_ref(args.session, 'reviewed', current_head)
        state['reviewed'] = current_head
        state['status'] = 'accepted'
        save_state(args.session, state)
        print(f"Session {args.session} accepted. Reviewed ref set to {current_head[:7]}")

def cmd_handoff(args):
    with SessionLock(args.session):
        state = load_state(args.session)
        if state['status'] != 'accepted':
            raise AgentSessionError(f"Session must be accepted before handoff. Current status: {state['status']}")
            
        if not state.get('reviewed'):
            raise AgentSessionError("Session has no reviewed ref.")
            
        check_git_clean()
        current_head = get_head_sha()
        if current_head != state['reviewed']:
            raise AgentSessionError("HEAD must be at the reviewed ref to perform handoff.")
            
        branch = get_current_branch()
        if branch != state['branch']:
            raise AgentSessionError(f"Not on the correct branch (expected {state['branch']}).")

    # Lock released, execute handoff
    print(f"Starting handoff for session {args.session}...")
    env = os.environ.copy()
    env['AGENT_SESSION_ID'] = args.session
    env['TELEGRAM_DATA_SOURCE'] = 'real'
    
    cmd = [os.path.join(REPO_ROOT, 'scripts', 'distribute-local.sh'), f"Handoff Session {args.session}"]
    sys.stdout.flush()
    os.execvpe(cmd[0], cmd, env)

def main():
    setup_signals()
    parser = argparse.ArgumentParser(description="Codex-Antigravity Session Wrapper")
    subparsers = parser.add_subparsers(dest="command", required=True)
    
    p_start = subparsers.add_parser("start")
    p_start.add_argument("--task-file", required=True)
    p_start.add_argument("--session", required=True)
    
    p_continue = subparsers.add_parser("continue")
    p_continue.add_argument("--session", required=True)
    p_continue.add_argument("--review-file", required=True)
    
    p_status = subparsers.add_parser("status")
    p_status.add_argument("--session", required=True)
    
    p_accept = subparsers.add_parser("accept")
    p_accept.add_argument("--session", required=True)
    
    p_handoff = subparsers.add_parser("handoff")
    p_handoff.add_argument("--session", required=True)
    
    args = parser.parse_args()
    
    try:
        if args.command == "start":
            cmd_start(args)
        elif args.command == "continue":
            cmd_continue(args)
        elif args.command == "status":
            cmd_status(args)
        elif args.command == "accept":
            cmd_accept(args)
        elif args.command == "handoff":
            cmd_handoff(args)
    except AgentSessionError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

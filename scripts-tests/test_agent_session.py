#!/usr/bin/env python3
import os
import shutil
import subprocess
import tempfile
import unittest
import json
import sys

# Add scripts directory to path to import agent-session (we will test it via subprocess to isolate env)
# Better yet, let's just invoke it via subprocess in our tests.

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(SCRIPT_DIR)
AGENT_SESSION_SCRIPT = os.path.join(REPO_ROOT, 'scripts', 'agent-session.py')

class TestAgentSession(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="agent-session-test-")
        self.repo_dir = os.path.join(self.test_dir, "repo")
        os.makedirs(self.repo_dir)
        
        # Init git repo
        self.run_git('init', '-b', 'main')
        self.run_git('config', 'user.name', 'Test')
        self.run_git('config', 'user.email', 'test@example.com')
        
        # Create initial commit and mock script
        self.write_file("README.md", "Initial")
        dist_script = os.path.join('scripts', 'distribute-local.sh')
        os.makedirs(os.path.join(self.repo_dir, 'scripts'), exist_ok=True)
        self.write_file(dist_script, "#!/bin/bash\necho 'Handoff called for '$AGENT_SESSION_ID\nexit 0\n")
        os.chmod(os.path.join(self.repo_dir, dist_script), 0o755)
        
        self.run_git('add', '.')
        self.run_git('commit', '-m', 'Initial commit')
        
        # Create fake agy
        self.fake_agy = os.path.join(self.test_dir, "fake-agy.sh")
        self.write_file(self.fake_agy, "#!/bin/bash\nprintenv > fake-agy.env\necho '{\"type\":\"fake-json\"}'\nexit 0\n")
        os.chmod(self.fake_agy, 0o755)
        
        self.env = os.environ.copy()
        self.env['AGY_BIN'] = self.fake_agy
        self.env['PATH'] = f"{self.test_dir}:{self.env.get('PATH', '')}"

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def write_file(self, path, content):
        full_path = os.path.join(self.repo_dir, path) if not os.path.isabs(path) else path
        with open(full_path, 'w') as f:
            f.write(content)

    def run_git(self, *args):
        subprocess.check_call(['git'] + list(args), cwd=self.repo_dir, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def run_wrapper(self, *args, **kwargs):
        check = kwargs.pop('check', True)
        cmd = [sys.executable, AGENT_SESSION_SCRIPT] + list(args)
        proc = subprocess.run(cmd, cwd=self.repo_dir, env=self.env, capture_output=True, text=True)
        if check and proc.returncode != 0:
            raise Exception(f"Command failed: {' '.join(cmd)}\nStdout: {proc.stdout}\nStderr: {proc.stderr}")
        return proc

    def test_start_clean(self):
        task_file = os.path.join(self.test_dir, "task.md")
        with open(task_file, 'w') as f:
            f.write("Do something")
        
        # Replace fake_agy to actually make a commit so it passes validation
        self.write_file(self.fake_agy, """#!/bin/bash
echo '{"message": "doing work"}'
echo 'change' > file.txt
git add file.txt
git commit -m "Work done

Agent: Antigravity
Agent-Session: session1"
exit 0
""")
        
        proc = self.run_wrapper('start', '--session', 'session1', '--task-file', task_file)
        self.assertIn("finished with status: completed", proc.stdout)
        
        # Check status
        proc = self.run_wrapper('status', '--session', 'session1')
        state = json.loads(proc.stdout)
        self.assertEqual(state['status'], 'completed')
        self.assertEqual(state['session_id'], 'session1')
        self.assertIsNotNone(state['base'])
        self.assertIsNotNone(state['result'])

    def test_start_dirty_tree(self):
        task_file = os.path.join(self.test_dir, "task.md")
        with open(task_file, 'w') as f:
            f.write("Do something")
        self.write_file("dirty.txt", "dirty")
        
        proc = self.run_wrapper('start', '--session', 'session2', '--task-file', task_file, check=False)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Working tree is dirty", proc.stderr)

    def test_accept_and_handoff(self):
        task_file = os.path.join(self.test_dir, "task.md")
        with open(task_file, 'w') as f:
            f.write("Do something")
        
        self.write_file(self.fake_agy, """#!/bin/bash
echo 'change' > file.txt
git add file.txt
git commit -m "Work done\n\nAgent: Antigravity\nAgent-Session: session3"
exit 0
""")
        self.run_wrapper('start', '--session', 'session3', '--task-file', task_file)
        
        # Accept
        proc = self.run_wrapper('accept', '--session', 'session3')
        self.assertIn("accepted", proc.stdout)
        
        proc = self.run_wrapper('handoff', '--session', 'session3')
        self.assertIn("Handoff called for session3", proc.stdout)

    def test_continue(self):
        task_file = os.path.join(self.test_dir, "task.md")
        with open(task_file, 'w') as f:
            f.write("Do something")
        
        self.write_file(self.fake_agy, """#!/bin/bash
echo 'change' > file.txt
git add file.txt
git commit -m "Work done\n\nAgent: Antigravity\nAgent-Session: session4"
exit 0
""")
        self.run_wrapper('start', '--session', 'session4', '--task-file', task_file)
        
        review_file = os.path.join(self.test_dir, "review.md")
        with open(review_file, 'w') as f:
            f.write("Fix this")
        
        self.write_file(self.fake_agy, """#!/bin/bash
echo 'change2' >> file.txt
git add file.txt
git commit -m "Fix done\n\nAgent: Antigravity\nAgent-Session: session4"
exit 0
""")
        proc = self.run_wrapper('continue', '--session', 'session4', '--review-file', review_file)
        self.assertIn("continue finished with status: completed", proc.stdout)

if __name__ == '__main__':
    unittest.main()

#!/bin/bash
set -e

echo "Setting up dev container..."

# Fix ownership of mounted cache directories (Docker volumes are created as root)
echo "Fixing permissions on mounted cache directories..."
sudo chown -R vscode:vscode /home/vscode/.sbt /home/vscode/.ivy2 /home/vscode/.cache /home/vscode/.claude 2>/dev/null || true

# Install sbt
echo "Installing sbt..."
curl -fL "https://github.com/sbt/sbt/releases/download/v1.12.0/sbt-1.12.0.tgz" | tar xz -C /tmp
sudo mv /tmp/sbt/bin/sbt /usr/local/bin/
sudo mv /tmp/sbt/bin/sbt-launch.jar /usr/local/bin/
sudo chmod +x /usr/local/bin/sbt
rm -rf /tmp/sbt

# Configure gh with token if available
if [ -n "$GITHUB_TOKEN" ]; then
  echo "Configuring GitHub CLI..."
  echo "GITHUB_TOKEN is set - gh CLI will use it for authentication."
  gh auth status
else
  echo "Warning: GITHUB_TOKEN not set. GitHub CLI will not be authenticated."
fi

# Configure git (without SSH)
git config --global user.name "${GIT_USER_NAME:-Dev Container User}"
git config --global user.email "${GIT_USER_EMAIL:-devcontainer@localhost}"

# Disable SSH agent forwarding explicitly
unset SSH_AUTH_SOCK
unset SSH_AGENT_PID

echo "Dev container setup complete!"
echo "Java version: $(java -version 2>&1 | head -n 1)"
echo "sbt version: $(sbt --version 2>&1 | grep 'sbt version')"
echo "gh version: $(gh --version | head -n 1)"
echo ""
echo "Note: Claude Code will run with auto-approved permissions in this container."

# Add claude alias with auto-approved permissions
echo 'alias claude="claude --dangerously-skip-permissions"' >> ~/.bashrc

#!/bin/bash
set -e

echo "Running post-create setup..."

# Override git config with environment variables if provided
if [ -n "$GIT_USER_NAME" ]; then
  git config --global user.name "$GIT_USER_NAME"
fi
if [ -n "$GIT_USER_EMAIL" ]; then
  git config --global user.email "$GIT_USER_EMAIL"
fi

# Add claude-yolo alias
echo 'alias claude-yolo="claude --dangerously-skip-permissions"' >> ~/.bashrc

echo "Post-create setup complete."

# Coordinator Strict Delegation Rule

The Coordinator must never write code, edit application files, or execute tests directly.
All technical work, bug fixes, refactoring, build debugging, and test runs MUST be delegated to the swarm workers (@dev, @tech-architect, @qa, @infra) via post_message.

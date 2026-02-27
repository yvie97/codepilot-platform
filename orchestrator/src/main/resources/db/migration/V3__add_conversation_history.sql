-- Conversation history persistence (§5.3):
--   Stores the full multi-turn message list for a step as a JSON array,
--   e.g. [{"role":"user","content":"..."},{"role":"assistant","content":"..."}].
--
--   Purpose: crash recovery.  If a worker dies mid-step, the scheduler
--   re-claims the step and AgentLoop resumes from the last saved turn
--   instead of restarting the entire conversation from scratch.
ALTER TABLE steps
    ADD COLUMN conversation_history TEXT;

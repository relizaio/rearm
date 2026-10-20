-- TPF-43: one number per board. The number and key are drawn under the board lock (AgentTaskService
-- stampKey) and the draw starts past the board's highest stored number; the counter only grows since
-- TPF-42. This index refuses a duplicate a bug would store. Rerunnable (IF NOT EXISTS, the V93 convention).
CREATE UNIQUE INDEX IF NOT EXISTS agent_tasks_board_number
    ON rearm.agent_tasks ((record_data->>'board'), ((record_data->>'number')::int))
    WHERE record_data->>'number' IS NOT NULL;

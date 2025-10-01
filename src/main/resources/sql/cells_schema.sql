-- Optimized PostgreSQL schema for the Cells system
-- This script creates all necessary tables with improved performance and data integrity

-- Main cells table with UUID primary key
CREATE TABLE IF NOT EXISTS cells (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    leader_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    description TEXT NOT NULL DEFAULT '',
    announcement TEXT NOT NULL DEFAULT '',
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    last_updated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    level INT NOT NULL DEFAULT 1,
    balance NUMERIC(20,6) NOT NULL DEFAULT 0
);

-- Members table with optimized role constraints
CREATE TABLE IF NOT EXISTS cell_members (
    cell_id UUID NOT NULL REFERENCES cells(id) ON DELETE CASCADE,
    player_id UUID NOT NULL,
    role VARCHAR(12) NOT NULL DEFAULT 'MEMBER'
        CHECK (role IN ('MEMBER','CAPTAIN','CO_LEADER','LEADER')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (cell_id, player_id)
);

-- Invitations table with automatic expiration handling
CREATE TABLE IF NOT EXISTS cell_invitations (
    cell_id UUID NOT NULL REFERENCES cells(id) ON DELETE CASCADE,
    inviter_id UUID NOT NULL,
    invitee_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (cell_id, invitee_id)
) WITH (autovacuum_enabled = true);

-- Optimized indexes for critical query patterns
CREATE INDEX IF NOT EXISTS idx_cells_leader_id ON cells(leader_id);
CREATE INDEX IF NOT EXISTS idx_cells_name ON cells(name);
CREATE INDEX IF NOT EXISTS idx_cells_public ON cells(is_public) WHERE is_public = true;
CREATE INDEX IF NOT EXISTS idx_cells_updated ON cells(last_updated);

CREATE INDEX IF NOT EXISTS idx_cell_members_player ON cell_members(player_id);
CREATE INDEX IF NOT EXISTS idx_cell_members_cell ON cell_members(cell_id);
CREATE INDEX IF NOT EXISTS idx_cell_members_role ON cell_members(role);

CREATE INDEX IF NOT EXISTS idx_cell_invitations_invitee ON cell_invitations(invitee_id);
CREATE INDEX IF NOT EXISTS idx_cell_invitations_cell ON cell_invitations(cell_id);
CREATE INDEX IF NOT EXISTS idx_cell_invitations_expiry ON cell_invitations(created_at);

-- Function to automatically update last_updated timestamp
CREATE OR REPLACE FUNCTION update_cells_last_updated()
RETURNS TRIGGER AS $$
BEGIN
    NEW.last_updated = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically update last_updated on cells table
DROP TRIGGER IF EXISTS trigger_update_cells_last_updated ON cells;
CREATE TRIGGER trigger_update_cells_last_updated
    BEFORE UPDATE ON cells
    FOR EACH ROW
    EXECUTE FUNCTION update_cells_last_updated();

-- Function to clean up expired invitations (older than 3 days)
CREATE OR REPLACE FUNCTION cleanup_expired_invitations()
RETURNS INTEGER AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM cell_invitations
    WHERE created_at < NOW() - INTERVAL '3 days';
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Create a scheduled job to run cleanup (requires pg_cron extension)
-- This is optional and can be managed by the application instead
-- SELECT cron.schedule('cleanup-invitations', '0 2 * * *', 'SELECT cleanup_expired_invitations();');

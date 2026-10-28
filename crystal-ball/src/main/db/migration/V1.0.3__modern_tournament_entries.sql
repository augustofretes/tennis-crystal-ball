-- Preserve modern Olympic and Next Gen entry categories from community CSVs.
ALTER TYPE tournament_entry ADD VALUE IF NOT EXISTS 'ITF';
ALTER TYPE tournament_entry ADD VALUE IF NOT EXISTS 'UP';
ALTER TYPE tournament_entry ADD VALUE IF NOT EXISTS 'NG';

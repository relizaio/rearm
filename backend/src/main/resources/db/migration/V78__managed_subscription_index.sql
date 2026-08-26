-- The subscription a team's owned-component toggle materialises is found by its
-- managedByTeam marker on every team save. Without an index that is a sequential
-- scan of the whole cross-tenant table per edit; V42 indexes every other key
-- this table is queried by, and V77 set the precedent for both halves of this.
--
-- UNIQUE, and partial, for the reason V77's teams_org_name gives: the service
-- check is a read followed by a write, so two concurrent team saves can both see
-- "no managed row" and both create one. A second row would leave one
-- subscription updated and the other still delivering, invisibly. Ordinary
-- subscriptions carry no marker at all, so the predicate keeps them out of the
-- constraint entirely rather than making them collide on NULL.
CREATE UNIQUE INDEX notification_subscriptions_managed_by_team_idx
    ON rearm.notification_subscriptions ((record_data->>'managedByTeam'))
    WHERE record_data->>'managedByTeam' IS NOT NULL;

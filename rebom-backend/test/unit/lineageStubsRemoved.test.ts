import { describe, it, expect } from 'vitest';
import { buildASTSchema, coerceInputValue, GraphQLInputType, GraphQLObjectType, isInputObjectType, isObjectType, isInterfaceType } from 'graphql';
import typeDefs from '../../src/schema.graphql';
import * as BomService from '../../src/bomService';

// SCORE-54 (SCORE-50d) T-1: the SCORE-50 compatibility stubs are gone from the
// schema. bomLineage, setBomLineageOwners, their types and
// RebomOptions.ownerComponent no longer exist, so a caller that still uses one
// gets a GraphQL validation error instead of an unowned answer. Replaces the
// SCORE-51 stub tests (bomLineageStubs.test.ts).
const schema = buildASTSchema(typeDefs);
const OWNER = 'aaaaaaaa-0000-4000-8000-000000000001';

describe('the schema', () => {
    it('has no bomLineage query', () => {
        const query = schema.getQueryType() as GraphQLObjectType;
        expect(Object.keys(query.getFields())).not.toContain('bomLineage');
    });

    it('has no setBomLineageOwners mutation', () => {
        const mutation = schema.getMutationType() as GraphQLObjectType;
        expect(Object.keys(mutation.getFields())).not.toContain('setBomLineageOwners');
    });

    it.each(['BomLineage', 'BomLineageOwnerInput', 'BomLineageOwnerMode', 'BomLineageOwnerOutcome', 'BomLineageOwnerResult'])(
        'has no type %s', (name) => {
            expect(schema.getType(name)).toBeUndefined();
        });

    it('has no type with a field named ownerComponent', () => {
        const types = Object.values(schema.getTypeMap())
            .filter(type => isInputObjectType(type) || isObjectType(type) || isInterfaceType(type));
        expect(types.length).toBeGreaterThan(0);
        for (const type of types) {
            const fields = (type as any).getFields();
            expect(Object.keys(fields), `${type.name} has ownerComponent`).not.toContain('ownerComponent');
        }
    });

    it('refuses RebomOptions.ownerComponent on coercion, naming the field and the type', () => {
        const errors: string[] = [];
        coerceInputValue({ ownerComponent: OWNER }, schema.getType('RebomOptions') as GraphQLInputType,
            (_path, _invalid, error) => errors.push(error.message));

        expect(errors).toHaveLength(1);
        expect(errors[0]).toContain('ownerComponent');
        expect(errors[0]).toContain('RebomOptions');
    });
});

describe('BomService', () => {
    it('exports neither bomLineage nor setBomLineageOwners', () => {
        expect(Object.keys(BomService)).not.toContain('bomLineage');
        expect(Object.keys(BomService)).not.toContain('setBomLineageOwners');
    });
});

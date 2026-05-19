DROP INDEX IF EXISTS rebom.bom_serial_version_and_org;
CREATE UNIQUE INDEX bom_serial_version_and_org on rebom.boms (
  organization,
  (meta ->> 'serialNumber'),
  (meta ->> 'bomVersion')
);
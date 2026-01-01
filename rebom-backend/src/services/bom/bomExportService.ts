import ExcelJS from 'exceljs';

export async function bomToExcel(bom: any): Promise<string> {
  const fields = [
    'name',
    'group',
    'version',
    'purl',
    'author',
    'license'
  ];

  const workbook = new ExcelJS.Workbook();
  const worksheet = workbook.addWorksheet('SBOM');

  worksheet.addRow(fields);

  const boms = Array.isArray(bom) ? bom : [bom];
  if (boms.length !== 0) {

    boms.forEach(b => {
      if (b && b.components && Array.isArray(b.components)) {
        b.components.forEach((component: any) => {
          const row = fields.map(field => {
            if (field === 'license') {
              if (component.licenses && Array.isArray(component.licenses)) {
                const licenseIds = component.licenses
                  .map((license: any) => {
                    if (license.license && license.license.id) return license.license.id;
                    if (license.license && license.license.name) return license.license.name;
                    if (typeof license === 'string') return license;
                    return '';
                  })
                  .filter(Boolean);
                return licenseIds.join('; ');
              }
              return '';
            }
            if (field === 'author') {
              const author = component.publisher || component.author || '';
              return author;
            }
            return component[field] || '';
          });
          worksheet.addRow(row);
        });
      }
    });
  }

  const xlsxBuffer = await workbook.xlsx.writeBuffer()
  const xlsxContent = Buffer.from(xlsxBuffer).toString('base64')
  return xlsxContent
}

export function bomToCsv(bom: any): string {

  const fields = [
    'name',
    'group',
    'version',
    'purl',
    'author',
    'license'
  ];

  const header = fields.join(',');
  let rows: string[] = [];

  const boms = Array.isArray(bom) ? bom : [bom];
  if (boms.length === 0) return '';

  boms.forEach(b => {
    if (b && b.components && Array.isArray(b.components)) {
      b.components.forEach((component: any) => {
        const row = fields.map(field => {
          if (field === 'license') {
            if (component.licenses && Array.isArray(component.licenses)) {
              const licenseIds = component.licenses
                .map((license: any) => {
                  if (license.license && license.license.id) return license.license.id;
                  if (license.license && license.license.name) return license.license.name;
                  if (typeof license === 'string') return license;
                  return '';
                })
                .filter(Boolean);
              return licenseIds.join('; ');
            }
            return '';
          }
          if (field === 'author') {
            const author = component.publisher || component.author || '';
            return author;
          }
          return component[field] || '';
        });
        
        const csvRow = row.map(value => {
          const stringValue = String(value);
          if (stringValue.includes(',') || stringValue.includes('"') || stringValue.includes('\n')) {
            return `"${stringValue.replace(/"/g, '""')}"`;
          }
          return stringValue;
        }).join(',');
        
        rows.push(csvRow);
      });
    }
  });

  return [header, ...rows].join('\n');
}

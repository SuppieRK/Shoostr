try {
  const names = ['REVIEWED_REVISION', 'SOURCE_REVISION', 'PROMOTED_REVISION', 'REVIEWED_TREE', 'PROMOTED_TREE'];
  if (names.some(name => !/^[a-f0-9]{40}$/.test(process.env[name] ?? ''))
      || process.env.SOURCE_REVISION !== process.env.REVIEWED_REVISION
      || process.env.PROMOTED_REVISION !== process.env.REVIEWED_REVISION
      || process.env.PROMOTED_TREE !== process.env.REVIEWED_TREE) {
    throw new Error('Promotion must preserve the exact reviewed commit and tree; review changed source again');
  }
  console.log(`Promotion matches reviewed revision ${process.env.REVIEWED_REVISION}`);
} catch (error_) {
  console.error(error_.message);
  process.exitCode = 1;
}

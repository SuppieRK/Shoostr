export default {
    themes: ['github-dark', 'github-light'],
    plugins: [{
        name: 'preserve-blank-lines-in-markdown',
        hooks: {
            postprocessRenderedLine({line, renderData}) {
                if (line.text) return;
                const code = renderData.lineAst.children.find(node =>
                    node.type === 'element' && node.properties.className?.includes('code'));
                // Keep a blank line without adding a second newline during Markdown conversion.
                if (code?.children[0]?.type === 'text' && code.children[0].value === '\n') {
                    code.children[0].value = ' ';
                }
            },
        },
    }],
};

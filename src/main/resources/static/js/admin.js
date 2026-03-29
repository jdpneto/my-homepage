document.addEventListener('DOMContentLoaded', function() {
    var textarea = document.getElementById('content-editor');
    if (textarea) {
        new EasyMDE({
            element: textarea,
            spellChecker: false,
            status: false,
            theme: 'dark',
            minHeight: '300px',
            autosave: {
                enabled: false
            },
            toolbar: [
                'bold', 'italic', 'heading', '|',
                'code', 'quote', 'unordered-list', 'ordered-list', '|',
                'link', 'image', '|',
                'preview', 'side-by-side', 'fullscreen', '|',
                'guide'
            ]
        });
    }
});

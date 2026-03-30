document.addEventListener('DOMContentLoaded', function() {
    var gallerySection = document.getElementById('image-gallery-section');
    if (!gallerySection) return;

    var ownerType = gallerySection.dataset.ownerType;
    var ownerId = gallerySection.dataset.ownerId;
    var grid = document.getElementById('image-gallery-grid');
    var uploadBtn = document.getElementById('image-upload-btn');
    var fileInput = document.getElementById('image-file-input');
    var toast = document.getElementById('image-copied-toast');

    function getCsrfToken() {
        var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    function clearChildren(element) {
        while (element.firstChild) {
            element.removeChild(element.firstChild);
        }
    }

    function loadImages() {
        fetch('/admin/api/images?ownerType=' + encodeURIComponent(ownerType) + '&ownerId=' + encodeURIComponent(ownerId))
            .then(function(res) { return res.json(); })
            .then(function(images) {
                clearChildren(grid);
                if (images.length === 0) {
                    var empty = document.createElement('div');
                    empty.className = 'image-gallery-empty';
                    empty.textContent = 'No images uploaded yet';
                    grid.appendChild(empty);
                    return;
                }
                images.forEach(function(img) {
                    var item = document.createElement('div');
                    item.className = 'image-gallery-item';

                    var imgEl = document.createElement('img');
                    imgEl.src = img.url;
                    imgEl.alt = img.filename;
                    imgEl.title = 'Click to copy Markdown';
                    item.appendChild(imgEl);

                    var delBtn = document.createElement('button');
                    delBtn.className = 'delete-btn';
                    delBtn.textContent = '\u00D7';
                    delBtn.title = 'Delete image';
                    delBtn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        if (confirm('Delete this image?')) {
                            deleteImage(img.id);
                        }
                    });
                    item.appendChild(delBtn);

                    item.addEventListener('click', function() {
                        var markdown = '![' + img.filename + '](' + img.url + ')';
                        navigator.clipboard.writeText(markdown).then(function() {
                            showToast('Copied!');
                        });
                    });

                    grid.appendChild(item);
                });
            });
    }

    function deleteImage(id) {
        fetch('/admin/api/images/' + encodeURIComponent(id), {
            method: 'DELETE',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() }
        }).then(function() {
            loadImages();
        });
    }

    function showToast(message) {
        toast.textContent = message;
        toast.classList.add('show');
        setTimeout(function() {
            toast.classList.remove('show');
        }, 1500);
    }

    uploadBtn.addEventListener('click', function() {
        fileInput.click();
    });

    fileInput.addEventListener('change', function() {
        if (!fileInput.files.length) return;
        var formData = new FormData();
        formData.append('file', fileInput.files[0]);
        formData.append('ownerType', ownerType);
        formData.append('ownerId', ownerId);

        fetch('/admin/api/images', {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': getCsrfToken() },
            body: formData
        })
        .then(function(res) {
            if (!res.ok) return res.json().then(function(data) { throw new Error(data.error); });
            return res.json();
        })
        .then(function() {
            loadImages();
            fileInput.value = '';
        })
        .catch(function(err) {
            alert(err.message || 'Upload failed');
            fileInput.value = '';
        });
    });

    loadImages();
});

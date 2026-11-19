// htmx leaves the page as it is when the site answers with an error, so the
// answer is shown above the page instead
document.addEventListener('htmx:responseError', (event) => {
    const problem = document.getElementById('problem');
    if (problem) {
        problem.textContent = event.detail.xhr.responseText || 'That didn\'t work, try again.';
        problem.hidden = false;
    }
});
document.addEventListener('htmx:sendError', () => {
    const problem = document.getElementById('problem');
    if (problem) {
        problem.textContent = 'That didn\'t work, try again.';
        problem.hidden = false;
    }
});

// a sync can take a while without a good connection
document.addEventListener('submit', (event) => {
    const button = event.target.querySelector('button[data-busy]');
    if (button) {
        button.disabled = true;
        button.textContent = button.dataset.busy;
    }
});

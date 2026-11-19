(() => {
    const pad = document.getElementById('pad');
    const field = document.getElementById('signature');
    const context = pad.getContext('2d');
    context.lineWidth = 3;
    context.lineCap = 'round';
    context.strokeStyle = '#111';
    let drawing = false;
    let signed = false;

    const point = (event) => {
        const box = pad.getBoundingClientRect();
        return [(event.clientX - box.left) * pad.width / box.width, (event.clientY - box.top) * pad.height / box.height];
    };
    pad.addEventListener('pointerdown', (event) => {
        drawing = true;
        pad.setPointerCapture(event.pointerId);
        context.beginPath();
        context.moveTo(...point(event));
    });
    pad.addEventListener('pointermove', (event) => {
        if (drawing) {
            // a tap alone draws nothing, only a stroke signs
            signed = true;
            context.lineTo(...point(event));
            context.stroke();
        }
    });
    const end = () => { drawing = false; };
    pad.addEventListener('pointerup', end);
    pad.addEventListener('pointercancel', end);
    document.getElementById('clear').addEventListener('click', () => {
        context.clearRect(0, 0, pad.width, pad.height);
        signed = false;
    });
    document.getElementById('sign').addEventListener('submit', () => {
        field.value = signed ? pad.toDataURL('image/png') : '';
    });
})();

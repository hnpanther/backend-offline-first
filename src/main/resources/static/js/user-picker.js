/**
 * Searchable multi-select user picker (offline, no external deps).
 * Expects global USER_PICKER_DATA = [{ id, label, search }]
 */
(function () {
    'use strict';

    function normalize(text) {
        return (text || '').toString().trim().toLowerCase();
    }

    class UserPicker {
        constructor(root) {
            this.root = root;
            this.fieldName = root.dataset.name;
            this.allUsers = window.USER_PICKER_DATA || [];
            this.selected = new Set(
                (root.dataset.selected || '').split(',').map(s => s.trim()).filter(Boolean)
            );
            this.focusIndex = -1;
            this.filtered = [];

            this.chipsEl = root.querySelector('.user-picker__chips');
            this.searchEl = root.querySelector('.user-picker__search');
            this.dropdownEl = root.querySelector('.user-picker__dropdown');
            this.hiddenEl = root.querySelector('.user-picker__hidden');
            this.countEl = root.querySelector('.user-picker__count');
            this.clearEl = root.querySelector('.user-picker__clear');

            this.render();
            this.bindEvents();
        }

        bindEvents() {
            this.searchEl.addEventListener('focus', () => this.open());
            this.searchEl.addEventListener('input', () => {
                this.focusIndex = -1;
                this.renderDropdown();
                this.open();
            });
            this.searchEl.addEventListener('keydown', (e) => this.onSearchKeydown(e));

            this.clearEl.addEventListener('click', (e) => {
                e.preventDefault();
                this.selected.clear();
                this.render();
            });

            document.addEventListener('click', (e) => {
                if (!this.root.contains(e.target)) this.close();
            });
        }

        onSearchKeydown(e) {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                this.focusIndex = Math.min(this.focusIndex + 1, this.filtered.length - 1);
                this.renderDropdown();
                this.open();
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                this.focusIndex = Math.max(this.focusIndex - 1, 0);
                this.renderDropdown();
                this.open();
            } else if (e.key === 'Enter') {
                e.preventDefault();
                if (this.focusIndex >= 0 && this.filtered[this.focusIndex]) {
                    this.toggle(this.filtered[this.focusIndex].id);
                }
            } else if (e.key === 'Escape') {
                this.close();
                this.searchEl.blur();
            }
        }

        open() {
            this.root.classList.add('user-picker--open');
            this.renderDropdown();
        }

        close() {
            this.root.classList.remove('user-picker--open');
            this.focusIndex = -1;
        }

        toggle(userId) {
            if (this.selected.has(userId)) {
                this.selected.delete(userId);
            } else {
                this.selected.add(userId);
            }
            this.render();
            this.searchEl.focus();
        }

        getFiltered() {
            const q = normalize(this.searchEl.value);
            if (!q) return this.allUsers;
            return this.allUsers.filter(u => normalize(u.search).includes(q) || normalize(u.label).includes(q));
        }

        renderDropdown() {
            this.filtered = this.getFiltered();
            this.dropdownEl.innerHTML = '';

            if (this.allUsers.length === 0) {
                this.dropdownEl.innerHTML = '<div class="user-picker__empty">ابتدا کاربر ایجاد کنید.</div>';
                return;
            }

            if (this.filtered.length === 0) {
                this.dropdownEl.innerHTML = '<div class="user-picker__empty">نتیجه‌ای یافت نشد</div>';
                return;
            }

            this.filtered.forEach((user, index) => {
                const isSelected = this.selected.has(user.id);
                const option = document.createElement('div');
                option.className = 'user-picker__option' +
                    (isSelected ? ' user-picker__option--selected' : '') +
                    (index === this.focusIndex ? ' user-picker__option--focused' : '');
                option.dataset.userId = user.id;
                option.innerHTML =
                    '<div class="user-picker__option-label">' +
                    '  <div>' + escapeHtml(user.label) + '</div>' +
                    (user.username ? '<div class="user-picker__option-sub">' + escapeHtml(user.username) + '</div>' : '') +
                    '</div>' +
                    (isSelected ? '<i class="bi bi-check-circle-fill user-picker__option-check"></i>' : '<i class="bi bi-plus-circle user-picker__option-check" style="opacity:.35"></i>');

                option.addEventListener('click', () => this.toggle(user.id));
                this.dropdownEl.appendChild(option);
            });
        }

        renderChips() {
            this.chipsEl.innerHTML = '';
            this.allUsers.forEach(user => {
                if (!this.selected.has(user.id)) return;
                const chip = document.createElement('span');
                chip.className = 'user-picker__chip';
                chip.innerHTML =
                    '<span class="user-picker__chip-text">' + escapeHtml(user.label) + '</span>' +
                    '<button type="button" class="user-picker__chip-remove" aria-label="حذف">&times;</button>';
                chip.querySelector('.user-picker__chip-remove').addEventListener('click', () => {
                    this.selected.delete(user.id);
                    this.render();
                });
                this.chipsEl.appendChild(chip);
            });
        }

        renderHiddenInputs() {
            this.hiddenEl.innerHTML = '';
            this.selected.forEach(id => {
                const input = document.createElement('input');
                input.type = 'hidden';
                input.name = this.fieldName;
                input.value = id;
                this.hiddenEl.appendChild(input);
            });
        }

        renderCount() {
            if (this.countEl) {
                this.countEl.textContent = this.selected.size + ' نفر انتخاب شده';
            }
        }

        render() {
            this.renderChips();
            this.renderHiddenInputs();
            this.renderCount();
            this.renderDropdown();
        }
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    window.initUserPickers = function () {
        document.querySelectorAll('.user-picker').forEach(el => {
            if (!el._userPicker) {
                el._userPicker = new UserPicker(el);
            }
        });
    };

    document.addEventListener('DOMContentLoaded', window.initUserPickers);
})();

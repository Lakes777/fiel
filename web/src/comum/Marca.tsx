import { Link } from 'react-router';

/** A balança em equilíbrio, com o fiel no meio: é o logo */
export function Balanca({ className }: { className?: string }) {
    return (
        <svg
            className={className}
            viewBox="0 0 32 32"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
        >
            <path d="M16 4v22M9 27h14M6 9h20" />
            <path d="M6 9l-4 9h8zM26 9l-4 9h8z" />
            <circle cx="16" cy="5" r="1.6" fill="currentColor" />
        </svg>
    );
}

export function Marca({ para = '/' }: { para?: string }) {
    return (
        <Link to={para} className="marca" aria-label="Fiel, início">
            <Balanca />
            <span>Fiel</span>
        </Link>
    );
}
